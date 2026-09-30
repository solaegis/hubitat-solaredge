# Hubitat-solaredge

## 2.0.0 — Breaking changes

Quota-aware rewrite. See [README.md](README.md) for migration.

| Attribute | v1.x | v2.0 |
|-----------|------|------|
| `battery` | string `"51.0%"` | number `51` |
| `storage_charge` | string `"51.0%"` | number `51` |
| `pv_power`, `grid_power`, `load_power` | string `"2.5 kW"` | number watts |
| `storage_power` | string, always positive | number watts, negative when charging |
| `storage_status` | comment said Charging/Discharging | API values: `Active`/`Idle`/`Disabled` |
| `storage_direction` | *(new)* | `charging` / `discharging` / `idle` |
| `apiStatus`, `apiCallsToday`, `dataAge` | *(new)* | observability |
| `energy` | *(new)* | kWh (EnergyMeter capability) |

**Preferences removed:** three per-endpoint 1–59 minute enums.

**Preferences added:** Polling Intensity, Daily API call budget, Night mode, Site timezone, Custom interval overrides.

**Behavior changes:**
- `clearData` no longer zeroes `lifetime`.
- Polling uses a single dispatcher with a client-side daily call ledger (default budget 150).
- Failed polls preserve last-good values instead of coercing null to zero.
- 401/403 halts polling until preferences are saved again.

## 1.2.0

- Added Battery Storage

## 1.1.2

- Bug fixes for updated timestamp and tile refresh.

## 1.1.1

- Update to include flow direction coloring.

## 1.1.0

- Update with improved settings and extra endpoint that includes power flow direction.

## 1.0.0

- Initial release of driver.
