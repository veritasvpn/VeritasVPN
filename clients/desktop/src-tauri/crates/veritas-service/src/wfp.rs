//! Persistent Windows Filtering Platform lock.
//! Numbers from failed calls are logged. They are not returned to the window.

use std::net::Ipv4Addr;
use std::ptr;

use windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::{
    FwpmEngineClose0, FwpmEngineOpen0, FwpmFilterAdd0, FwpmFilterCreateEnumHandle0,
    FwpmFilterDeleteByKey0, FwpmFilterDestroyEnumHandle0, FwpmFilterEnum0, FwpmFreeMemory0,
    FwpmProviderAdd0, FwpmProviderDeleteByKey0, FwpmSubLayerAdd0, FwpmSubLayerDeleteByKey0,
    FwpmTransactionAbort0, FwpmTransactionBegin0, FwpmTransactionCommit0, FWPM_ACTION0,
    FWPM_DISPLAY_DATA0, FWPM_FILTER0, FWPM_FILTER_CONDITION0, FWPM_FILTER_ENUM_TEMPLATE0,
    FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT, FWPM_FILTER_FLAG_PERSISTENT, FWPM_PROVIDER0,
    FWPM_PROVIDER_FLAG_PERSISTENT, FWPM_SESSION0, FWPM_SUBLAYER0, FWPM_SUBLAYER_FLAG_PERSISTENT,
    FWP_ACTION_BLOCK, FWP_ACTION_PERMIT, FWP_CONDITION_FLAG_IS_LOOPBACK, FWP_MATCH_EQUAL,
    FWP_UINT16, FWP_UINT64, FWP_UINT8, FWP_V4_ADDR_AND_MASK, FWP_V4_ADDR_MASK, FWP_V6_ADDR_AND_MASK,
    FWP_V6_ADDR_MASK, FWPM_CONDITION_FLAGS,
    FWPM_CONDITION_IP_LOCAL_INTERFACE, FWPM_CONDITION_IP_PROTOCOL, FWPM_CONDITION_IP_REMOTE_ADDRESS,
    FWPM_CONDITION_IP_REMOTE_PORT, FWPM_LAYER_ALE_AUTH_CONNECT_V4, FWPM_LAYER_ALE_AUTH_CONNECT_V6,
    FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4, FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
};
use windows_sys::core::GUID;

type ConditionValue =
    windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::FWP_CONDITION_VALUE0;

use crate::wfp_plan::{rules, Endpoint, LockPlan, Rule};

const PROVIDER: GUID = GUID::from_u128(0x5b1d0c2a_7e44_4f1a_9c3d_2a6e8b0f11c4);
const SUBLAYER: GUID = GUID::from_u128(0x6c2e1d3b_8f55_401b_ad4e_3b7f9c1022d5);
const RPC_C_AUTHN_WINNT: u32 = 10;
const FWP_E_ALREADY_EXISTS: u32 = 0x8032_0009;

pub fn arm(plan: &LockPlan) -> Result<(), u32> {
    let engine = open()?;
    let result = arm_in(engine, plan);
    unsafe { FwpmEngineClose0(engine) };
    result
}

pub fn disarm() -> Result<(), u32> {
    let engine = open()?;
    let result = delete_our_objects(engine);
    unsafe { FwpmEngineClose0(engine) };
    result
}

pub fn is_armed() -> bool {
    let Ok(engine) = open() else {
        return false;
    };
    let armed = filter_count(engine) > 0;
    unsafe { FwpmEngineClose0(engine) };
    armed
}

fn arm_in(engine: windows_sys::Win32::Foundation::HANDLE, plan: &LockPlan) -> Result<(), u32> {
    let err = unsafe { FwpmTransactionBegin0(engine, 0) };
    if err != 0 {
        return Err(err);
    }
    let result = (|| {
        delete_our_objects(engine)?;
        add_provider(engine)?;
        add_sublayer(engine)?;
        for rule in rules(plan) {
            add_rule(engine, plan.tunnel_luid, &rule, weight_of(&rule))?;
        }
        Ok(())
    })();
    if result.is_ok() {
        let err = unsafe { FwpmTransactionCommit0(engine) };
        if err != 0 {
            return Err(err);
        }
        Ok(())
    } else {
        unsafe { FwpmTransactionAbort0(engine) };
        result
    }
}

fn open() -> Result<windows_sys::Win32::Foundation::HANDLE, u32> {
    let mut handle = ptr::null_mut();
    let session = FWPM_SESSION0::default();
    let err = unsafe {
        FwpmEngineOpen0(
            ptr::null(),
            RPC_C_AUTHN_WINNT,
            ptr::null(),
            &session,
            &mut handle,
        )
    };
    if err != 0 {
        Err(err)
    } else {
        Ok(handle)
    }
}

fn add_provider(engine: windows_sys::Win32::Foundation::HANDLE) -> Result<(), u32> {
    let mut name = wide("VeritasVPN");
    let mut description = wide("VeritasVPN kill switch");
    let provider = FWPM_PROVIDER0 {
        providerKey: PROVIDER,
        displayData: FWPM_DISPLAY_DATA0 {
            name: name.as_mut_ptr(),
            description: description.as_mut_ptr(),
        },
        flags: FWPM_PROVIDER_FLAG_PERSISTENT,
        providerData: Default::default(),
        serviceName: ptr::null_mut(),
    };
    let err = unsafe { FwpmProviderAdd0(engine, &provider, ptr::null_mut()) };
    if err == 0 || err == FWP_E_ALREADY_EXISTS {
        Ok(())
    } else {
        Err(err)
    }
}

fn add_sublayer(engine: windows_sys::Win32::Foundation::HANDLE) -> Result<(), u32> {
    let mut name = wide("VeritasVPN");
    let mut provider = PROVIDER;
    let sublayer = FWPM_SUBLAYER0 {
        subLayerKey: SUBLAYER,
        displayData: FWPM_DISPLAY_DATA0 {
            name: name.as_mut_ptr(),
            description: ptr::null_mut(),
        },
        flags: FWPM_SUBLAYER_FLAG_PERSISTENT,
        providerKey: &mut provider,
        providerData: Default::default(),
        weight: u16::MAX,
    };
    let err = unsafe { FwpmSubLayerAdd0(engine, &sublayer, ptr::null_mut()) };
    if err == 0 || err == FWP_E_ALREADY_EXISTS {
        Ok(())
    } else {
        Err(err)
    }
}

fn weight_of(rule: &Rule) -> u8 {
    match rule {
        Rule::PermitLoopback => 15,
        Rule::PermitTunnel { .. } => 14,
        Rule::PermitWireGuard { .. } => 13,
        Rule::PermitStealth { .. } => 12,
        Rule::PermitDhcp => 11,
        Rule::PermitLocalNetwork => 10,
        Rule::PermitManagement { .. } | Rule::PermitManagementV6 { .. } | Rule::PermitWeb => 9,
        Rule::BlockDns => 2,
        Rule::BlockOther => 1,
    }
}

fn add_rule(
    engine: windows_sys::Win32::Foundation::HANDLE,
    luid: u64,
    rule: &Rule,
    weight: u8,
) -> Result<(), u32> {
    match rule {
        Rule::PermitLoopback => {
            for layer in connect_and_recv() {
                add_simple(engine, layer, weight, true, &loopback_condition())?;
            }
        }
        Rule::PermitTunnel { .. } => {
            for layer in connect_and_recv() {
                add_simple(engine, layer, weight, true, &interface_condition(luid))?;
            }
        }
        Rule::PermitWireGuard { addr, port } => {
            add_simple(
                engine,
                FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                weight,
                true,
                &udp_endpoint(*addr, *port, Some(luid)),
            )?;
        }
        Rule::PermitStealth { addr, port } => {
            add_simple(
                engine,
                FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                weight,
                true,
                &tcp_endpoint(*addr, *port),
            )?;
        }
        Rule::PermitDhcp => {
            for port in [67u16, 68] {
                add_simple(
                    engine,
                    FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                    weight,
                    true,
                    &udp_port(port),
                )?;
                add_simple(
                    engine,
                    FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4,
                    weight,
                    true,
                    &udp_port(port),
                )?;
            }
        }
        Rule::PermitLocalNetwork => {
            for (addr, mask) in private_ranges() {
                add_simple(
                    engine,
                    FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                    weight,
                    true,
                    &network(addr, mask),
                )?;
            }
        }
        Rule::PermitManagement { addr } => {
            add_simple(
                engine,
                FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                weight,
                true,
                &management_v4(*addr, luid),
            )?;
        }
        Rule::PermitManagementV6 { addr } => {
            add_simple(
                engine,
                FWPM_LAYER_ALE_AUTH_CONNECT_V6,
                weight,
                true,
                &management_v6(*addr, luid),
            )?;
        }
        Rule::PermitWeb => {
            for port in [80u16, 443] {
                add_simple(
                    engine,
                    FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                    weight,
                    true,
                    &proto_port(6, port),
                )?;
                add_simple(
                    engine,
                    FWPM_LAYER_ALE_AUTH_CONNECT_V6,
                    weight,
                    true,
                    &proto_port(6, port),
                )?;
            }
        }
        Rule::BlockDns => {
            for port in [53u16, 853] {
                for proto in [17u8, 6] {
                    add_simple(
                        engine,
                        FWPM_LAYER_ALE_AUTH_CONNECT_V4,
                        weight,
                        false,
                        &proto_port(proto, port),
                    )?;
                    add_simple(
                        engine,
                        FWPM_LAYER_ALE_AUTH_CONNECT_V6,
                        weight,
                        false,
                        &proto_port(proto, port),
                    )?;
                }
            }
        }
        Rule::BlockOther => {
            for layer in connect_and_recv() {
                add_simple(engine, layer, weight, false, &[])?;
            }
        }
    }
    Ok(())
}

fn connect_and_recv() -> [GUID; 4] {
    [
        FWPM_LAYER_ALE_AUTH_CONNECT_V4,
        FWPM_LAYER_ALE_AUTH_CONNECT_V6,
        FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V4,
        FWPM_LAYER_ALE_AUTH_RECV_ACCEPT_V6,
    ]
}

struct Held {
    conditions: Vec<FWPM_FILTER_CONDITION0>,
    masks: Vec<FWP_V4_ADDR_AND_MASK>,
    v6masks: Vec<FWP_V6_ADDR_AND_MASK>,
    luid: u64,
}

fn add_simple(
    engine: windows_sys::Win32::Foundation::HANDLE,
    layer: GUID,
    weight: u8,
    permit: bool,
    conditions: &[Cond],
) -> Result<(), u32> {
    let held = hold(conditions);
    let mut name = wide("VeritasVPN");
    let mut provider = PROVIDER;
    let mut weight_value = unsafe { std::mem::zeroed::<windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::FWP_VALUE0>() };
    weight_value.r#type = FWP_UINT8;
    weight_value.Anonymous.uint8 = weight;
    let mut filter = FWPM_FILTER0::default();
    filter.displayData = FWPM_DISPLAY_DATA0 {
        name: name.as_mut_ptr(),
        description: ptr::null_mut(),
    };
    filter.flags = FWPM_FILTER_FLAG_PERSISTENT | FWPM_FILTER_FLAG_CLEAR_ACTION_RIGHT;
    filter.providerKey = &mut provider;
    filter.layerKey = layer;
    filter.subLayerKey = SUBLAYER;
    filter.weight = weight_value;
    filter.numFilterConditions = held.conditions.len() as u32;
    filter.filterCondition = if held.conditions.is_empty() {
        ptr::null_mut()
    } else {
        held.conditions.as_ptr() as *mut _
    };
    filter.action = FWPM_ACTION0 {
        r#type: if permit { FWP_ACTION_PERMIT } else { FWP_ACTION_BLOCK },
        Anonymous: unsafe { std::mem::zeroed() },
    };
    let err = unsafe { FwpmFilterAdd0(engine, &filter, ptr::null_mut(), ptr::null_mut()) };
    let _ = held.luid;
    if err == 0 {
        Ok(())
    } else {
        Err(err)
    }
}

enum Cond {
    Loopback,
    Interface(u64),
    NotInterface(u64),
    Protocol(u8),
    Port(u16),
    Network(Ipv4Addr, u32),
    NetworkV6(std::net::Ipv6Addr),
}

fn loopback_condition() -> Vec<Cond> {
    vec![Cond::Loopback]
}

fn interface_condition(luid: u64) -> Vec<Cond> {
    vec![Cond::Interface(luid)]
}

fn udp_endpoint(addr: Ipv4Addr, port: u16, not_luid: Option<u64>) -> Vec<Cond> {
    let mut conds = vec![Cond::Protocol(17), Cond::Port(port), Cond::Network(addr, u32::MAX)];
    if let Some(luid) = not_luid {
        conds.push(Cond::NotInterface(luid));
    }
    conds
}

fn tcp_endpoint(addr: Ipv4Addr, port: u16) -> Vec<Cond> {
    vec![Cond::Protocol(6), Cond::Port(port), Cond::Network(addr, u32::MAX)]
}

fn udp_port(port: u16) -> Vec<Cond> {
    vec![Cond::Protocol(17), Cond::Port(port)]
}

fn proto_port(proto: u8, port: u16) -> Vec<Cond> {
    vec![Cond::Protocol(proto), Cond::Port(port)]
}

fn network(addr: Ipv4Addr, mask: u32) -> Vec<Cond> {
    vec![Cond::Network(addr, mask)]
}

fn management_v4(addr: Ipv4Addr, luid: u64) -> Vec<Cond> {
    vec![Cond::Network(addr, u32::MAX), Cond::NotInterface(luid)]
}

fn management_v6(addr: std::net::Ipv6Addr, luid: u64) -> Vec<Cond> {
    vec![Cond::NetworkV6(addr), Cond::NotInterface(luid)]
}

fn private_ranges() -> [(Ipv4Addr, u32); 5] {
    [
        ("10.0.0.0".parse().unwrap(), prefix_mask(8)),
        ("172.16.0.0".parse().unwrap(), prefix_mask(12)),
        ("192.168.0.0".parse().unwrap(), prefix_mask(16)),
        ("169.254.0.0".parse().unwrap(), prefix_mask(16)),
        ("224.0.0.0".parse().unwrap(), prefix_mask(4)),
    ]
}

fn prefix_mask(prefix: u8) -> u32 {
    if prefix == 0 {
        0
    } else {
        u32::MAX.checked_shl(32 - u32::from(prefix)).unwrap_or(0)
    }
}

fn hold(conds: &[Cond]) -> Held {
    let mut held = Held {
        conditions: Vec::new(),
        masks: Vec::new(),
        v6masks: Vec::new(),
        luid: 0,
    };
    for cond in conds {
        match cond {
            Cond::Interface(luid) | Cond::NotInterface(luid) => held.luid = *luid,
            Cond::Network(addr, mask) => held.masks.push(FWP_V4_ADDR_AND_MASK {
                addr: crate::wfp_plan::host_ipv4(*addr),
                mask: crate::wfp_plan::host_mask(*mask),
            }),
            Cond::NetworkV6(addr) => held.v6masks.push(FWP_V6_ADDR_AND_MASK {
                addr: addr.octets(),
                prefixLength: 128,
            }),
            _ => {}
        }
    }
    let mut mask_index = 0;
    let mut v6_index = 0;
    for cond in conds {
        let (field, match_type, value) = match cond {
            Cond::Loopback => (
                FWPM_CONDITION_FLAGS,
                windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::FWP_MATCH_FLAGS_ALL_SET,
                value_u32(FWP_CONDITION_FLAG_IS_LOOPBACK),
            ),
            Cond::Interface(_) => (
                FWPM_CONDITION_IP_LOCAL_INTERFACE,
                FWP_MATCH_EQUAL,
                value_u64(&held.luid),
            ),
            Cond::NotInterface(_) => (
                FWPM_CONDITION_IP_LOCAL_INTERFACE,
                windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::FWP_MATCH_NOT_EQUAL,
                value_u64(&held.luid),
            ),
            Cond::Protocol(proto) => (FWPM_CONDITION_IP_PROTOCOL, FWP_MATCH_EQUAL, value_u8(*proto)),
            Cond::Port(port) => (FWPM_CONDITION_IP_REMOTE_PORT, FWP_MATCH_EQUAL, value_u16(*port)),
            Cond::Network(_, _) => {
                let mask = &held.masks[mask_index];
                mask_index += 1;
                (
                    FWPM_CONDITION_IP_REMOTE_ADDRESS,
                    FWP_MATCH_EQUAL,
                    value_mask(mask),
                )
            }
            Cond::NetworkV6(_) => {
                let mask = &held.v6masks[v6_index];
                v6_index += 1;
                (
                    FWPM_CONDITION_IP_REMOTE_ADDRESS,
                    FWP_MATCH_EQUAL,
                    value_v6(mask),
                )
            }
        };
        held.conditions.push(FWPM_FILTER_CONDITION0 {
            fieldKey: field,
            matchType: match_type,
            conditionValue: value,
        });
    }
    held
}

fn value_u8(value: u8) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = FWP_UINT8;
    out.Anonymous.uint8 = value;
    out
}

fn value_u16(value: u16) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = FWP_UINT16;
    out.Anonymous.uint16 = value;
    out
}

fn value_u32(value: u32) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = windows_sys::Win32::NetworkManagement::WindowsFilteringPlatform::FWP_UINT32;
    out.Anonymous.uint32 = value;
    out
}

fn value_u64(value: &u64) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = FWP_UINT64;
    out.Anonymous.uint64 = value as *const u64 as *mut u64;
    out
}

fn value_mask(mask: &FWP_V4_ADDR_AND_MASK) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = FWP_V4_ADDR_MASK;
    out.Anonymous.v4AddrMask = mask as *const FWP_V4_ADDR_AND_MASK as *mut _;
    out
}

fn value_v6(mask: &FWP_V6_ADDR_AND_MASK) -> ConditionValue {
    let mut out = ConditionValue::default();
    out.r#type = FWP_V6_ADDR_MASK;
    out.Anonymous.v6AddrMask = mask as *const FWP_V6_ADDR_AND_MASK as *mut _;
    out
}

fn delete_our_objects(engine: windows_sys::Win32::Foundation::HANDLE) -> Result<(), u32> {
    let mut provider = PROVIDER;
    let mut template = FWPM_FILTER_ENUM_TEMPLATE0::default();
    template.providerKey = &mut provider;
    let mut enum_handle = ptr::null_mut();
    let err = unsafe { FwpmFilterCreateEnumHandle0(engine, &template, &mut enum_handle) };
    if err == 0 {
        let mut entries = ptr::null_mut();
        let mut count = 0u32;
        let err = unsafe { FwpmFilterEnum0(engine, enum_handle, 256, &mut entries, &mut count) };
        if err == 0 && !entries.is_null() {
            for index in 0..count {
                let filter = unsafe { &**entries.add(index as usize) };
                unsafe { FwpmFilterDeleteByKey0(engine, &filter.filterKey) };
            }
            unsafe { FwpmFreeMemory0(&mut entries as *mut _ as *mut *mut _) };
        }
        unsafe { FwpmFilterDestroyEnumHandle0(engine, enum_handle) };
    }
    unsafe {
        FwpmSubLayerDeleteByKey0(engine, &SUBLAYER);
        FwpmProviderDeleteByKey0(engine, &PROVIDER);
    }
    nrpt_clear();
    Ok(())
}

fn filter_count(engine: windows_sys::Win32::Foundation::HANDLE) -> u32 {
    let mut provider = PROVIDER;
    let mut template = FWPM_FILTER_ENUM_TEMPLATE0::default();
    template.providerKey = &mut provider;
    let mut enum_handle = ptr::null_mut();
    let err = unsafe { FwpmFilterCreateEnumHandle0(engine, &template, &mut enum_handle) };
    if err != 0 {
        return 0;
    }
    let mut entries = ptr::null_mut();
    let mut count = 0u32;
    unsafe {
        FwpmFilterEnum0(engine, enum_handle, 8, &mut entries, &mut count);
        if !entries.is_null() {
            FwpmFreeMemory0(&mut entries as *mut _ as *mut *mut _);
        }
        FwpmFilterDestroyEnumHandle0(engine, enum_handle);
    }
    count
}

const NRPT_KEY: &str = "SOFTWARE\\Policies\\Microsoft\\Windows NT\\DNSClient\\DnsPolicyConfig\\{8f3c2a10-6b4e-4c1a-9d2e-1a0b7c6d5e4f}";

pub fn nrpt_set(dns: Ipv4Addr) {
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegSetValueExW, HKEY_LOCAL_MACHINE, KEY_SET_VALUE,
        REG_DWORD, REG_OPTION_NON_VOLATILE, REG_SZ,
    };
    let wide_key = wide(NRPT_KEY);
    let mut key = ptr::null_mut();
    let err = unsafe {
        RegCreateKeyExW(
            HKEY_LOCAL_MACHINE,
            wide_key.as_ptr(),
            0,
            ptr::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            ptr::null(),
            &mut key,
            ptr::null_mut(),
        )
    };
    if err != 0 {
        crate::engine::log_code("nrpt", err);
        return;
    }
    let version = 2u32;
    let options = 0u32;
    unsafe {
        let name = wide(".");
        let servers = wide(&dns.to_string());
        RegSetValueExW(key, wide("Version").as_ptr(), 0, REG_DWORD, &version as *const u32 as *const u8, 4);
        RegSetValueExW(key, wide("ConfigOptions").as_ptr(), 0, REG_DWORD, &options as *const u32 as *const u8, 4);
        RegSetValueExW(
            key,
            wide("Name").as_ptr(),
            0,
            REG_SZ,
            name.as_ptr() as *const u8,
            (name.len() * 2) as u32,
        );
        RegSetValueExW(
            key,
            wide("GenericDNSServers").as_ptr(),
            0,
            REG_SZ,
            servers.as_ptr() as *const u8,
            (servers.len() * 2) as u32,
        );
        RegCloseKey(key);
    }
}

pub fn nrpt_clear() {
    use windows_sys::Win32::System::Registry::{RegDeleteTreeW, HKEY_LOCAL_MACHINE};
    let wide_key = wide(NRPT_KEY);
    unsafe { RegDeleteTreeW(HKEY_LOCAL_MACHINE, wide_key.as_ptr()) };
}

fn wide(value: &str) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    std::ffi::OsStr::new(value).encode_wide().chain(std::iter::once(0)).collect()
}

pub fn endpoints_from(config: &veritas_core::WgTunnelConfig) -> Vec<Endpoint> {
    [config.endpoint.as_str(), config.endpoint_lan.as_str(), config.endpoint_wan.as_str()]
        .into_iter()
        .filter_map(|endpoint| crate::engine::resolve_endpoint(endpoint).ok())
        .map(|(addr, port)| Endpoint { addr, port })
        .collect()
}
