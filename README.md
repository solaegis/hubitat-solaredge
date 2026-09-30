# hubitat-solaredge

Hubitat driver for the SolarEdge Cloud Monitoring API v1. Version 2.0 adds quota-aware polling, last-good value retention, and corrected battery/power attribute types.

## Installation

1. Copy [solaredge-driver.groovy](solaredge-driver.groovy) into Hubitat **Drivers Code**.
2. Create a **Virtual Device** and select **Solaredge Monitoring Driver**.
3. Enter Site Id, API Key, and save preferences.

## API quota

SolarEdge limits each site to **300 API calls per day** from the same source IP, with a maximum of **3 concurrent** requests. The API does **not** return `X-RateLimit-Remaining`; you must track usage client-side.

This driver enforces a configurable **daily call budget** (default **150**) via `atomicState`. A single dispatcher cron runs every 5 minutes (free ticks); HTTP calls are spent only when an endpoint is due and budget remains.

| Polling Intensity | Approx. calls/day | Use when |
|-------------------|-------------------|----------|
| Low | ~48 | Minimal cloud usage |
| Medium (default) | ~90 | Recommended balance |
| High | ~200 | More frequent updates; watch budget |
| Custom | varies | Set per-endpoint intervals |

**Night mode** (default on) suspends overview and power-flow polling between sunset and sunrise, and performs one post-sunset energy close-out call.

## Configuration

| Setting | Required | Default | Description |
|---------|----------|---------|-------------|
| Site Id | Yes | — | SolarEdge site identifier |
| API Key | Yes | — | Installer-generated monitoring API key |
| SolarEdge Monitor API URL | Yes | `https://monitoringapi.solaredge.com` | API base URL |
| Polling Intensity | Yes | Medium | Low / Medium / High / Custom |
| Daily API call budget | Yes | 150 | Hard ceiling (max 280) |
| Night mode | No | true | Reduce overnight polling |
| Site timezone | No | hub TZ | IANA timezone for site-local day boundary |
| Energy/Overview/Power flow interval | No | 30/30/15 min | Used when Intensity is Custom |
| Tile titles | No | empty | Optional dashboard tile headings |
| Display last updated | No | false | Show timestamp on tiles |
| Enable debug logging | No | false | Auto-disables after 30 minutes |

## Attributes

**Energy (Wh):** `production`, `consumption`, `self_consumption`, `feed_in`, `purchased`, `last_day`, `last_month`, `last_year`, `lifetime`

**Power (W):** `power`, `pv_power`, `grid_power`, `load_power`, `storage_power` (negative = charging)

**Battery:** `battery`, `storage_charge` (numeric %), `storage_status`, `storage_direction`

**Observability:** `apiStatus` (`ok` / `degraded` / `rate_limited` / `quota_guard` / `auth_failed`), `apiCallsToday`, `dataAge` (seconds since last good poll)

**Tiles:** `energy_tile`, `overview_tile`, `power_flow_tile`

## Dashboard

Select the device, template **Attribute**, then `energy_tile`, `overview_tile`, or `power_flow_tile`. Hubitat enforces a **1024-character** limit per attribute; tiles include compact markup and SolarEdge attribution per API terms.

### Example

![Tile Example](/tiles.png)

## Migrating from v1.x

See [CHANGELOG.md](CHANGELOG.md). Update dashboards and Rule Machine rules that compared string power values (e.g. `"2.5 kW"`) to use numeric watts instead. Re-save device preferences after upgrading to start the new dispatcher schedule.

## Audit report

See [docs/AUDIT.md](docs/AUDIT.md) for the full forensic audit and Modbus phase-2 blueprint.
