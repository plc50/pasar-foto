#!/usr/bin/env python3
"""Select a private IPv4 address and its directly connected network."""

from __future__ import annotations

import argparse
import ipaddress
import json
import subprocess


def run_ip(*args: str) -> list[dict]:
    result = subprocess.run(
        ["ip", "-j", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def interface_addresses() -> dict[str, list[ipaddress.IPv4Interface]]:
    addresses: dict[str, list[ipaddress.IPv4Interface]] = {}
    for interface in run_ip("-4", "addr", "show"):
        name = interface["ifname"]
        for info in interface.get("addr_info", []):
            if info.get("family") != "inet" or info.get("scope") != "global":
                continue
            address = ipaddress.ip_interface(f"{info['local']}/{info['prefixlen']}")
            if address.ip.is_private and not address.ip.is_loopback:
                addresses.setdefault(name, []).append(address)
    return addresses


def choose_address(override: str | None) -> tuple[str, ipaddress.IPv4Interface]:
    addresses = interface_addresses()
    if override:
        wanted = ipaddress.ip_address(override)
        for name, candidates in addresses.items():
            for candidate in candidates:
                if candidate.ip == wanted:
                    return name, candidate
        raise SystemExit(f"{override} is not a private address configured on this PC")

    routes = run_ip("-4", "route", "show", "default")
    routes.sort(key=lambda route: int(route.get("metric", 0)))
    for route in routes:
        interface = route.get("dev")
        candidates = addresses.get(interface, [])
        preferred = route.get("prefsrc")
        if preferred:
            for candidate in candidates:
                if str(candidate.ip) == preferred:
                    return interface, candidate
        if candidates:
            return interface, candidates[0]

    for interface, candidates in addresses.items():
        if candidates:
            return interface, candidates[0]

    raise SystemExit(
        "No private IPv4 network found. Connect both devices to the same Wi-Fi "
        "or set PASAR_FOTO_WIFI_HOST."
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host")
    args = parser.parse_args()
    interface, address = choose_address(args.host)
    print(f"{address.ip} {address.network} {interface}")


if __name__ == "__main__":
    main()
