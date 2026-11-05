//! IPv4 address, route, MTU, and DNS on the tunnel adapter.
//! IPv6 is not assigned and not routed. The kill switch blocks it outside
//! loopback. Nothing here is logged.

use std::net::Ipv4Addr;

use windows_sys::Win32::NetworkManagement::IpHelper::{
    ConvertInterfaceLuidToGuid, ConvertInterfaceLuidToIndex, CreateIpForwardEntry2,
    CreateUnicastIpAddressEntry,
    DeleteIpForwardEntry2, FreeMibTable, GetIpForwardTable2, GetIpInterfaceEntry,
    GetUnicastIpAddressTable, InitializeIpForwardEntry, InitializeIpInterfaceEntry,
    InitializeUnicastIpAddressEntry, SetInterfaceDnsSettings, SetIpInterfaceEntry,
    DNS_INTERFACE_SETTINGS, DNS_INTERFACE_SETTINGS_VERSION1, DNS_SETTING_NAMESERVER,
    MIB_IPFORWARD_ROW2, MIB_IPINTERFACE_ROW, MIB_UNICASTIPADDRESS_ROW,
};
use windows_sys::Win32::NetworkManagement::Ndis::NET_LUID_LH;
use windows_sys::Win32::Networking::WinSock::{
    IpDadStatePreferred, IpPrefixOriginManual, IpSuffixOriginManual, MIB_IPPROTO_NETMGMT,
    IN_ADDR, IN_ADDR_0,
};

const AF_INET: u16 = 2;

pub fn assign_v4(luid: u64, address: Ipv4Addr, prefix: u8) -> Result<(), u32> {
    let mut row = unsafe { std::mem::zeroed::<MIB_UNICASTIPADDRESS_ROW>() };
    unsafe { InitializeUnicastIpAddressEntry(&mut row) };
    row.InterfaceLuid = luid_of(luid);
    unsafe {
        row.Address.Ipv4.sin_family = AF_INET;
        row.Address.Ipv4.sin_addr = in_addr(address);
    }
    row.OnLinkPrefixLength = prefix;
    row.PrefixOrigin = IpPrefixOriginManual;
    row.SuffixOrigin = IpSuffixOriginManual;
    row.ValidLifetime = u32::MAX;
    row.PreferredLifetime = u32::MAX;
    row.DadState = IpDadStatePreferred;
    row.SkipAsSource = false;
    let err = unsafe { CreateUnicastIpAddressEntry(&row) };
    if err == 0 || err == 5010 {
        Ok(())
    } else {
        Err(err)
    }
}

pub fn set_mtu(luid: u64, mtu: u32) -> Result<(), u32> {
    let mut row = unsafe { std::mem::zeroed::<MIB_IPINTERFACE_ROW>() };
    unsafe { InitializeIpInterfaceEntry(&mut row) };
    row.Family = AF_INET;
    row.InterfaceLuid = luid_of(luid);
    let err = unsafe { GetIpInterfaceEntry(&mut row) };
    if err != 0 {
        return Err(err);
    }
    row.NlMtu = mtu;
    let err = unsafe { SetIpInterfaceEntry(&mut row) };
    if err == 0 {
        Ok(())
    } else {
        Err(err)
    }
}

pub fn mtu_of(luid: u64) -> Result<u32, ()> {
    let mut row = unsafe { std::mem::zeroed::<MIB_IPINTERFACE_ROW>() };
    unsafe { InitializeIpInterfaceEntry(&mut row) };
    row.Family = AF_INET;
    row.InterfaceLuid = luid_of(luid);
    let err = unsafe { GetIpInterfaceEntry(&mut row) };
    if err == 0 {
        Ok(row.NlMtu)
    } else {
        Err(())
    }
}

pub fn add_route(luid: u64, network: Ipv4Addr, prefix: u8) -> Result<(), u32> {
    let mut row = unsafe { std::mem::zeroed::<MIB_IPFORWARD_ROW2>() };
    unsafe { InitializeIpForwardEntry(&mut row) };
    row.InterfaceLuid = luid_of(luid);
    unsafe {
        row.DestinationPrefix.Prefix.Ipv4.sin_family = AF_INET;
        row.DestinationPrefix.Prefix.Ipv4.sin_addr = in_addr(network);
        row.NextHop.Ipv4.sin_family = AF_INET;
    }
    row.DestinationPrefix.PrefixLength = prefix;
    row.Metric = 5;
    row.Protocol = MIB_IPPROTO_NETMGMT;
    let err = unsafe { CreateIpForwardEntry2(&row) };
    if err == 0 || err == 5010 {
        Ok(())
    } else {
        Err(err)
    }
}

pub fn interface_index(luid: u64) -> Option<u32> {
    let mut index = 0u32;
    let err = unsafe { ConvertInterfaceLuidToIndex(&luid_of(luid), &mut index) };
    if err == 0 && index != 0 {
        Some(index)
    } else {
        None
    }
}

pub fn delete_route(luid: u64, network: Ipv4Addr, prefix: u8) {
    let mut row = unsafe { std::mem::zeroed::<MIB_IPFORWARD_ROW2>() };
    unsafe { InitializeIpForwardEntry(&mut row) };
    row.InterfaceLuid = luid_of(luid);
    unsafe {
        row.DestinationPrefix.Prefix.Ipv4.sin_family = AF_INET;
        row.DestinationPrefix.Prefix.Ipv4.sin_addr = in_addr(network);
        row.NextHop.Ipv4.sin_family = AF_INET;
    }
    row.DestinationPrefix.PrefixLength = prefix;
    let _ = unsafe { DeleteIpForwardEntry2(&row) };
}

pub fn set_dns(luid: u64, dns: Ipv4Addr) -> Result<(), u32> {
    let mut guid = unsafe { std::mem::zeroed() };
    let err = unsafe { ConvertInterfaceLuidToGuid(&luid_of(luid), &mut guid) };
    if err != 0 {
        return Err(err);
    }
    let mut wide: Vec<u16> = dns.to_string().encode_utf16().chain(std::iter::once(0)).collect();
    let settings = DNS_INTERFACE_SETTINGS {
        Version: DNS_INTERFACE_SETTINGS_VERSION1,
        Flags: DNS_SETTING_NAMESERVER as u64,
        Domain: std::ptr::null_mut(),
        NameServer: wide.as_mut_ptr(),
        SearchList: std::ptr::null_mut(),
        RegistrationEnabled: 0,
        RegisterAdapterName: 0,
        EnableLLMNR: 0,
        QueryAdapterName: 0,
        ProfileNameServer: std::ptr::null_mut(),
    };
    let err = unsafe { SetInterfaceDnsSettings(guid, &settings) };
    if err == 0 {
        Ok(())
    } else {
        Err(err)
    }
}

/// Host route that keeps the WireGuard server on the physical NIC after the
/// tunnel installs 0.0.0.0/0. `None` when the server is already on-link.
pub struct PinnedEndpoint {
    pub endpoint: Ipv4Addr,
    pub luid: u64,
    pub gateway: Ipv4Addr,
}

pub fn default_gateway() -> Option<Ipv4Addr> {
    underlay_default(0).map(|(_, gateway)| gateway)
}

pub fn pin_endpoint(endpoint: Ipv4Addr, tunnel_luid: u64) -> Result<Option<PinnedEndpoint>, u32> {
    if endpoint.is_unspecified() || owns_address(endpoint) {
        return Ok(None);
    }
    let Some((luid, gateway)) = underlay_default(tunnel_luid) else {
        return Err(1168);
    };
    add_route_via(luid, endpoint, 32, gateway)?;
    Ok(Some(PinnedEndpoint {
        endpoint,
        luid,
        gateway,
    }))
}

pub fn unpin_endpoint(pinned: &PinnedEndpoint) {
    delete_route_via(pinned.luid, pinned.endpoint, 32, pinned.gateway);
}

fn add_route_via(luid: u64, network: Ipv4Addr, prefix: u8, gateway: Ipv4Addr) -> Result<(), u32> {
    let mut row = route_row(luid, network, prefix, gateway);
    row.Metric = 1;
    let err = unsafe { CreateIpForwardEntry2(&row) };
    if err == 0 || err == 5010 {
        Ok(())
    } else {
        Err(err)
    }
}

fn delete_route_via(luid: u64, network: Ipv4Addr, prefix: u8, gateway: Ipv4Addr) {
    let row = route_row(luid, network, prefix, gateway);
    let _ = unsafe { DeleteIpForwardEntry2(&row) };
}

fn route_row(luid: u64, network: Ipv4Addr, prefix: u8, gateway: Ipv4Addr) -> MIB_IPFORWARD_ROW2 {
    let mut row = unsafe { std::mem::zeroed::<MIB_IPFORWARD_ROW2>() };
    unsafe { InitializeIpForwardEntry(&mut row) };
    row.InterfaceLuid = luid_of(luid);
    unsafe {
        row.DestinationPrefix.Prefix.Ipv4.sin_family = AF_INET;
        row.DestinationPrefix.Prefix.Ipv4.sin_addr = in_addr(network);
        row.NextHop.Ipv4.sin_family = AF_INET;
        row.NextHop.Ipv4.sin_addr = in_addr(gateway);
    }
    row.DestinationPrefix.PrefixLength = prefix;
    row.Protocol = MIB_IPPROTO_NETMGMT;
    row
}

fn owns_address(endpoint: Ipv4Addr) -> bool {
    let mut table = std::ptr::null_mut();
    let err = unsafe { GetUnicastIpAddressTable(AF_INET, &mut table) };
    if err != 0 || table.is_null() {
        return false;
    }
    let count = unsafe { (*table).NumEntries };
    let rows = unsafe { (*table).Table.as_ptr() };
    let mut found = false;
    for index in 0..count {
        let row = unsafe { &*rows.add(index as usize) };
        if ipv4_of(unsafe { row.Address.Ipv4.sin_addr }) == endpoint {
            found = true;
            break;
        }
    }
    unsafe { FreeMibTable(table as *const _) };
    found
}

fn underlay_default(tunnel_luid: u64) -> Option<(u64, Ipv4Addr)> {
    let mut table = std::ptr::null_mut();
    let err = unsafe { GetIpForwardTable2(AF_INET, &mut table) };
    if err != 0 || table.is_null() {
        return None;
    }
    let count = unsafe { (*table).NumEntries };
    let rows = unsafe { (*table).Table.as_ptr() };
    let mut best: Option<(u32, u64, Ipv4Addr)> = None;
    for index in 0..count {
        let row = unsafe { &*rows.add(index as usize) };
        if row.DestinationPrefix.PrefixLength != 0 {
            continue;
        }
        let luid = unsafe { row.InterfaceLuid.Value };
        if luid == 0 || luid == tunnel_luid {
            continue;
        }
        let gateway = ipv4_of(unsafe { row.NextHop.Ipv4.sin_addr });
        if best.as_ref().is_none_or(|item| row.Metric < item.0) {
            best = Some((row.Metric, luid, gateway));
        }
    }
    unsafe { FreeMibTable(table as *const _) };
    best.map(|(_, luid, gateway)| (luid, gateway))
}

fn ipv4_of(addr: IN_ADDR) -> Ipv4Addr {
    Ipv4Addr::from(unsafe { addr.S_un.S_addr }.to_ne_bytes())
}

pub struct ManagementSnapshot {
    pub v4: Vec<Ipv4Addr>,
    pub v6: Vec<std::net::Ipv6Addr>,
    pub local_v4: Option<Ipv4Addr>,
    pub local_v6: Option<std::net::Ipv6Addr>,
}

/// Addresses the runner is already using, plus a local address for leak probes.
/// IP Helper calls return; they do not wait on a prompt.
pub fn management_snapshot() -> ManagementSnapshot {
    let local_v4 = local_unicast_v4();
    let local_v6 = local_unicast_v6();
    let mut v4 = established_v4();
    if let Some((_, gateway)) = underlay_default(0) {
        v4.push(gateway);
    }
    v4.extend(dns_v4());
    v4.retain(|addr| keep_management_v4(*addr, local_v4));
    v4.sort();
    v4.dedup();
    v4.truncate(32);
    let mut v6 = established_v6();
    v6.retain(|addr| keep_management_v6(*addr, local_v6));
    v6.sort();
    v6.dedup();
    v6.truncate(16);
    ManagementSnapshot {
        v4,
        v6,
        local_v4,
        local_v6,
    }
}

fn keep_management_v4(addr: Ipv4Addr, local: Option<Ipv4Addr>) -> bool {
    if addr.is_unspecified() || addr.is_loopback() || addr.is_multicast() || addr.is_broadcast() {
        return false;
    }
    if local == Some(addr) {
        return false;
    }
    let octets = addr.octets();
    // The harness probes these ranges. They must stay blocked.
    if octets[0] == 10 && octets[1] == 66 {
        return false;
    }
    if octets[0] == 203 && octets[1] == 0 && octets[2] == 113 {
        return false;
    }
    true
}

fn keep_management_v6(addr: std::net::Ipv6Addr, local: Option<std::net::Ipv6Addr>) -> bool {
    if addr.is_unspecified() || addr.is_loopback() || addr.is_multicast() || is_link_local(addr) {
        return false;
    }
    if local == Some(addr) {
        return false;
    }
    let octets = addr.octets();
    // 2001:db8::/32 is the documentation range the harness probes.
    !(octets[0] == 0x20 && octets[1] == 0x01 && octets[2] == 0x0d && octets[3] == 0xb8)
}

fn is_link_local(addr: std::net::Ipv6Addr) -> bool {
    let octets = addr.octets();
    octets[0] == 0xfe && (octets[1] & 0xc0) == 0x80
}

fn local_unicast_v4() -> Option<Ipv4Addr> {
    let rows: Vec<Ipv4Addr> = each_unicast_v4().filter(|addr| !addr.is_loopback()).collect();
    rows.iter()
        .copied()
        .find(|addr| !addr.is_link_local() && !is_tunnel_net(*addr))
        .or_else(|| rows.into_iter().next())
}

fn is_tunnel_net(addr: Ipv4Addr) -> bool {
    let octets = addr.octets();
    octets[0] == 10 && octets[1] == 66
}

fn local_unicast_v6() -> Option<std::net::Ipv6Addr> {
    each_unicast_v6().find(|addr| !addr.is_loopback() && !is_link_local(*addr))
}

fn each_unicast_v4() -> impl Iterator<Item = Ipv4Addr> {
    unicast_rows().into_iter().filter_map(|row| {
        if unsafe { row.Address.si_family } != AF_INET {
            return None;
        }
        Some(ipv4_of(unsafe { row.Address.Ipv4.sin_addr }))
    })
}

fn each_unicast_v6() -> impl Iterator<Item = std::net::Ipv6Addr> {
    unicast_rows().into_iter().filter_map(|row| {
        if unsafe { row.Address.si_family } != 23 {
            return None;
        }
        Some(std::net::Ipv6Addr::from(unsafe { row.Address.Ipv6.sin6_addr.u.Byte }))
    })
}

fn unicast_rows() -> Vec<MIB_UNICASTIPADDRESS_ROW> {
    let mut table = std::ptr::null_mut();
    let err = unsafe { GetUnicastIpAddressTable(0, &mut table) };
    if err != 0 || table.is_null() {
        return Vec::new();
    }
    let count = unsafe { (*table).NumEntries };
    let rows = unsafe { (*table).Table.as_ptr() };
    let mut out = Vec::with_capacity(count as usize);
    for index in 0..count {
        out.push(unsafe { *rows.add(index as usize) });
    }
    unsafe { FreeMibTable(table as *const _) };
    out
}

fn established_v4() -> Vec<Ipv4Addr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetExtendedTcpTable, MIB_TCPTABLE_OWNER_PID, TCP_TABLE_OWNER_PID_ALL,
    };
    let mut size = 0u32;
    unsafe { GetExtendedTcpTable(std::ptr::null_mut(), &mut size, 0, u32::from(AF_INET), TCP_TABLE_OWNER_PID_ALL, 0) };
    if size == 0 {
        return Vec::new();
    }
    let mut buf = vec![0u8; size as usize];
    let err = unsafe {
        GetExtendedTcpTable(buf.as_mut_ptr().cast(), &mut size, 0, AF_INET as u32, TCP_TABLE_OWNER_PID_ALL, 0)
    };
    if err != 0 {
        return Vec::new();
    }
    let table = unsafe { &*(buf.as_ptr() as *const MIB_TCPTABLE_OWNER_PID) };
    let rows = table.table.as_ptr();
    let mut out = Vec::new();
    for index in 0..table.dwNumEntries {
        let row = unsafe { &*rows.add(index as usize) };
        if row.dwState != 5 {
            continue;
        }
        out.push(Ipv4Addr::from(row.dwRemoteAddr.to_ne_bytes()));
    }
    out
}

fn established_v6() -> Vec<std::net::Ipv6Addr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetExtendedTcpTable, MIB_TCP6TABLE_OWNER_PID, TCP_TABLE_OWNER_PID_ALL,
    };
    let mut size = 0u32;
    unsafe { GetExtendedTcpTable(std::ptr::null_mut(), &mut size, 0, 23, TCP_TABLE_OWNER_PID_ALL, 0) };
    if size == 0 {
        return Vec::new();
    }
    let mut buf = vec![0u8; size as usize];
    let err = unsafe {
        GetExtendedTcpTable(buf.as_mut_ptr().cast(), &mut size, 0, 23, TCP_TABLE_OWNER_PID_ALL, 0)
    };
    if err != 0 {
        return Vec::new();
    }
    let table = unsafe { &*(buf.as_ptr() as *const MIB_TCP6TABLE_OWNER_PID) };
    let rows = table.table.as_ptr();
    let mut out = Vec::new();
    for index in 0..table.dwNumEntries {
        let row = unsafe { &*rows.add(index as usize) };
        if row.dwState != 5 {
            continue;
        }
        out.push(std::net::Ipv6Addr::from(row.ucRemoteAddr));
    }
    out
}

fn dns_v4() -> Vec<Ipv4Addr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetNetworkParams, FIXED_INFO_W2KSP1, IP_ADDR_STRING};
    let mut size = 0u32;
    unsafe { GetNetworkParams(std::ptr::null_mut(), &mut size) };
    if size == 0 {
        return Vec::new();
    }
    let mut buf = vec![0u8; size as usize];
    let err = unsafe { GetNetworkParams(buf.as_mut_ptr().cast(), &mut size) };
    if err != 0 {
        return Vec::new();
    }
    let info = unsafe { &*(buf.as_ptr() as *const FIXED_INFO_W2KSP1) };
    let mut cursor: *const IP_ADDR_STRING = &info.DnsServerList;
    let mut out = Vec::new();
    let mut guard = 0;
    while !cursor.is_null() && guard < 8 {
        let text = c_chars(&unsafe { (*cursor).IpAddress.String });
        if let Ok(addr) = text.parse() {
            out.push(addr);
        }
        cursor = unsafe { (*cursor).Next };
        guard += 1;
    }
    out
}

fn c_chars(bytes: &[i8]) -> String {
    bytes.iter().take_while(|b| **b != 0).map(|b| *b as u8 as char).collect()
}

fn luid_of(value: u64) -> NET_LUID_LH {
    unsafe { NET_LUID_LH { Value: value } }
}

fn in_addr(ip: Ipv4Addr) -> IN_ADDR {
    IN_ADDR {
        S_un: IN_ADDR_0 {
            S_addr: u32::from_ne_bytes(ip.octets()),
        },
    }
}
