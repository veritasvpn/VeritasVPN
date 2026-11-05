//! IPv4 address, route, MTU, and DNS on the tunnel adapter.
//! IPv6 is not assigned and not routed. The kill switch blocks it outside
//! loopback. Nothing here is logged.

use std::net::Ipv4Addr;

use windows_sys::Win32::NetworkManagement::IpHelper::{
    ConvertInterfaceLuidToGuid, CreateIpForwardEntry2, CreateUnicastIpAddressEntry,
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
