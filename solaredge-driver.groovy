/***********************************************************************************************************************
 *
 *  Hubitat device driver for SolarEdge Cloud Monitoring API v1.
 *  Quota-aware polling with last-good value retention.
 *
 *  License: GNU GENERAL PUBLIC LICENSE Version 3
 *  Changelog: https://github.com/funzie19/hubitat-solaredge/blob/master/CHANGELOG.md
 *
 ***********************************************************************************************************************/
private version() { return "2.0.0" }

metadata {
    definition(
        name: "Solaredge Monitoring Driver",
        namespace: "funzie19.solaredge",
        author: "Joseph Kregloh",
        importUrl: "https://raw.githubusercontent.com/funzie19/hubitat-solaredge/master/solaredge-driver.groovy",
        description: "Quota-aware SolarEdge monitoring driver with last-good value retention."
    ) {
        capability "Power Meter"
        capability "Battery"
        capability "EnergyMeter"
        capability "Refresh"

        command "updateTiles"
        command "getEnergy"
        command "getOverview"
        command "getSitePowerFlow"
        command "clearData"

        attribute "production", "number"
        attribute "consumption", "number"
        attribute "self_consumption", "number"
        attribute "feed_in", "number"
        attribute "purchased", "number"
        attribute "last_day", "number"
        attribute "last_month", "number"
        attribute "last_year", "number"
        attribute "lifetime", "number"
        attribute "energy", "number"
        attribute "grid_power", "number"
        attribute "load_power", "number"
        attribute "pv_power", "number"

        attribute "storage_status", "string"
        attribute "storage_direction", "string"
        attribute "storage_power", "number"
        attribute "storage_charge", "number"
        attribute "battery", "number"

        attribute "apiStatus", "string"
        attribute "apiCallsToday", "number"
        attribute "dataAge", "number"

        attribute "energy_tile", "string"
        attribute "overview_tile", "string"
        attribute "power_flow_tile", "string"
    }

    preferences {
        section("Configure SolarEdge API connection") {
            input("site_id", "number", title: "Site Id", description: "Site Id to monitor.", required: true)
            input("api_key", "text", title: "API Key", description: "Installer-generated API key with site access.", required: true)
            input("url", "text", title: "SolarEdge Monitor API URL", description: "Monitoring API base URL.", required: true, defaultValue: "https://monitoringapi.solaredge.com")
        }

        section("Polling and quota") {
            input("polling_intensity", "enum", title: "Polling Intensity", options: [
                "Low": "~48 API calls/day",
                "Medium": "~90 API calls/day (recommended)",
                "High": "~200 API calls/day",
                "Custom": "Set per-endpoint intervals below"
            ], defaultValue: "Medium", required: true)
            input("daily_budget", "number", title: "Daily API call budget", description: "Hard ceiling (SolarEdge limit is 300/day). Max 280.", defaultValue: 150, range: "50..280", required: true)
            input("night_mode", "bool", title: "Night mode", description: "Suspend real-time polling between sunset and sunrise; retain one post-sunset energy close-out.", defaultValue: true)
            input("site_timezone", "text", title: "Site timezone (optional)", description: "IANA timezone for site-local day boundary, e.g. America/Los_Angeles. Defaults to hub timezone.", required: false)
            input("energy_interval_minutes", "number", title: "Energy interval (Custom, minutes)", description: "Used when Polling Intensity is Custom.", defaultValue: 30, range: "15..120")
            input("overview_interval_minutes", "number", title: "Overview interval (Custom, minutes)", defaultValue: 30, range: "15..120")
            input("power_flow_interval_minutes", "number", title: "Power flow interval (Custom, minutes)", defaultValue: 15, range: "5..60")
        }

        section("Dashboard tiles") {
            input("energy_title", "text", title: "Daily Energy Details Title", required: false)
            input("overview_title", "text", title: "Production Overview Title", required: false)
            input("power_flow_title", "text", title: "Power Flow Title", required: false)
            input(name: "display_last_updated", type: "bool", title: "Display last updated timestamp?", defaultValue: false)
            input(name: "debug", type: "bool", title: "Enable debug logging (auto-disables after 30 min)", defaultValue: false)
        }
    }
}

def installed() {
    log.info "SolarEdge Monitoring v${version()} installed."
    initializeState()
    clearData()
    state.version = version()
}

def uninstalled() {
    unschedule()
}

def updated() {
    if (dbg()) log.debug "Settings updated."
    initializeState()
    unschedule()
    atomicState.authHalted = false
    atomicState.nightEnergyDone = false

    if (!settings.site_id || !settings.api_key) {
        log.warn "Site Id and API Key are required before polling can start."
        return
    }

    schedule("10 */5 * ? * *", "pollDispatcher")
    if (settings.debug) {
        state.debugLogging = true
        runIn(1800, "logsOff")
    }
    pollDispatcher()
    state.version = version()
}

def logsOff() {
    state.debugLogging = false
    log.info "Debug logging auto-disabled after 30 minutes."
}

def refresh() {
    if (atomicState.authHalted) {
        log.warn "Polling halted (auth_failed) — fix API key and save preferences."
        return
    }
    if (dbg()) log.debug "Manual refresh requested."
    if (spendCall("energy")) queryEnergyEndpoint()
    if (spendCall("overview")) queryOverviewEndpoint()
    if (spendCall("powerFlow")) querySitePowerFlowEndpoint()
    updateTiles()
}

def getEnergy() {
    if (spendCall("energy")) queryEnergyEndpoint()
    updateTiles()
}

def getOverview() {
    if (spendCall("overview")) queryOverviewEndpoint()
    updateTiles()
}

def getSitePowerFlow() {
    if (spendCall("powerFlow")) querySitePowerFlowEndpoint()
    updateTiles()
}

def clearData() {
    if (dbg()) log.debug "Clearing volatile metrics (lifetime preserved)."
    [
        "feed_in", "self_consumption", "purchased", "consumption", "production", "power",
        "last_day", "last_month", "last_year", "energy",
        "grid_power", "load_power", "pv_power",
        "storage_power", "storage_charge", "battery"
    ].each { name -> sendEvent(name: name, value: 0) }
    sendEvent(name: "storage_status", value: "unknown")
    sendEvent(name: "storage_direction", value: "idle")
    updateTiles()
}

def pollDispatcher() {
    if (atomicState.authHalted) return

    resetQuotaIfNewDay()
    emitApiCallsToday()

    boolean solarDay = isSolarDay()
    if (settings.night_mode && atomicState.wasSolarDay == true && !solarDay) {
        atomicState.nightEnergyDone = false
    }
    atomicState.wasSolarDay = solarDay

    Map intervals = resolveIntervals()
    boolean night = settings.night_mode && !solarDay

    if (isEndpointDue("energy", intervals.energy)) {
        if (spendCall("energy")) queryEnergyEndpoint()
    }

    if (!night) {
        if (isEndpointDue("overview", intervals.overview)) {
            if (spendCall("overview")) queryOverviewEndpoint()
        }
        if (isEndpointDue("powerFlow", intervals.powerFlow)) {
            if (spendCall("powerFlow")) querySitePowerFlowEndpoint()
        }
    } else if (settings.night_mode && !atomicState.nightEnergyDone) {
        if (spendCall("energy")) {
            queryEnergyEndpoint()
            atomicState.nightEnergyDone = true
        }
    }

    updateDataAge()
    updateTiles()
}

def queryEnergyEndpoint() {
    if (dbg()) log.debug "Gathering energy metrics."
    markEndpointPolled("energy")

    String today = siteToday()
    Map params = [
        uri: settings.url,
        path: "/site/${settings.site_id}/energyDetails",
        query: [timeUnit: "DAY", startTime: "${today} 00:00:00", endTime: "${today} 23:59:59", api_key: settings.api_key],
        timeout: 30,
        contentType: "application/json"
    ]

    try {
        httpGet(params) { resp ->
            if (dbg()) log.debug "energyDetails status: ${resp.status}"
            def meters = resp.data?.energyDetails?.meters
            if (!meters) {
                log.warn "energyDetails: empty meters array — preserving last-good values."
                return
            }
            meters.each { meter ->
                def value = meter?.values?.getAt(0)?.value
                if (value == null) {
                    if (dbg()) log.debug "${meter?.type}: no data yet — preserving last-good"
                    return
                }
                switch (meter?.type) {
                    case "FeedIn":
                        emitCumulative("feed_in", value, "Wh")
                        break
                    case "SelfConsumption":
                        emitCumulative("self_consumption", value, "Wh")
                        break
                    case "Purchased":
                        emitCumulative("purchased", value, "Wh")
                        break
                    case "Consumption":
                        emitCumulative("consumption", value, "Wh")
                        break
                    case "Production":
                        emitCumulative("production", value, "Wh")
                        break
                }
            }
            markSuccess()
        }
    } catch (Exception e) {
        onFailure("energyDetails", e)
    }
}

def queryOverviewEndpoint() {
    if (dbg()) log.debug "Gathering overview metrics."
    markEndpointPolled("overview")

    Map params = [
        uri: settings.url,
        path: "/site/${settings.site_id}/overview",
        query: [api_key: settings.api_key],
        timeout: 30,
        contentType: "application/json"
    ]

    try {
        httpGet(params) { resp ->
            if (dbg()) log.debug "overview status: ${resp.status}"
            def overview = resp.data?.overview
            if (!overview) {
                log.warn "overview: empty response — preserving last-good values."
                return
            }
            emitNumber("power", overview?.currentPower?.power, 5, "W")
            emitCumulative("last_day", overview?.lastDayData?.energy, "Wh")
            emitCumulative("last_month", overview?.lastMonthData?.energy, "Wh")
            emitCumulative("last_year", overview?.lastYearData?.energy, "Wh")
            emitCumulative("lifetime", overview?.lifeTimeData?.energy, "Wh")
            def dayWh = overview?.lastDayData?.energy
            if (dayWh != null) {
                emitNumber("energy", Math.round((dayWh as double) / 1000 * 1000) / 1000, 0.001, "kWh")
            }
            markSuccess()
        }
    } catch (Exception e) {
        onFailure("overview", e)
    }
}

def querySitePowerFlowEndpoint() {
    if (dbg()) log.debug "Gathering site power flow data."
    markEndpointPolled("powerFlow")

    Map params = [
        uri: settings.url,
        path: "/site/${settings.site_id}/currentPowerFlow",
        query: [api_key: settings.api_key],
        timeout: 30,
        contentType: "application/json"
    ]

    try {
        httpGet(params) { resp ->
            if (dbg()) log.debug "currentPowerFlow status: ${resp.status}"
            def flow = resp.data?.siteCurrentPowerFlow
            if (!flow) {
                log.warn "currentPowerFlow: empty response — preserving last-good values."
                return
            }

            String unit = flow?.unit
            def conns = flow?.connections ?: []
            boolean storageCharging = conns.any { it?.to?.toString()?.equalsIgnoreCase("STORAGE") }
            boolean storageDischarging = conns.any { it?.from?.toString()?.equalsIgnoreCase("STORAGE") }
            boolean gridImport = conns.any { it?.from?.toString()?.equalsIgnoreCase("GRID") }
            boolean gridExport = conns.any { it?.from?.toString()?.equalsIgnoreCase("LOAD") && it?.to?.toString()?.equalsIgnoreCase("GRID") }

            if (flow?.PV) {
                emitNumber("pv_power", toWatts(flow.PV?.currentPower, unit), 5, "W")
            }
            if (flow?.GRID) {
                emitNumber("grid_power", toWatts(flow.GRID?.currentPower, unit), 5, "W")
                state.flow_direction = gridExport ? "green" : (gridImport ? "red" : "gray")
            }
            if (flow?.LOAD) {
                emitNumber("load_power", toWatts(flow.LOAD?.currentPower, unit), 5, "W")
            }
            if (flow?.STORAGE) {
                BigDecimal mag = toWatts(flow.STORAGE?.currentPower, unit)
                if (mag != null) {
                    emitNumber("storage_power", storageCharging ? -mag : mag, 5, "W")
                }
                emitNumber("battery", flow.STORAGE?.chargeLevel, 1, "%")
                emitNumber("storage_charge", flow.STORAGE?.chargeLevel, 1, "%")
                emitString("storage_status", flow.STORAGE?.status)
                emitString("storage_direction", storageCharging ? "charging" : (storageDischarging ? "discharging" : "idle"))
                if (flow.STORAGE?.critical) {
                    log.warn "Battery below critical SoC threshold (${flow.STORAGE?.chargeLevel}%)."
                }
            }
            markSuccess()
        }
    } catch (Exception e) {
        onFailure("currentPowerFlow", e)
    }
}

def updateTiles() {
    if (dbg()) log.debug "Updating tile information."
    state.last_updated = new Date().format("yyyy-MM-dd HH:mm:ss", siteTimeZone())
    boolean stale = isDataStale()
    String staleNote = stale ? " <span style='color:#888;font-size:11px;'>(stale ${device.currentValue("dataAge") ?: "?"}s)</span>" : ""

    String energyTile = "<div style='font-size:13px'><table width='100%'>"
    if (settings.energy_title) energyTile += "<tr><td colspan='2' style='text-align:center'>${settings.energy_title}</td></tr>"
    energyTile += tileRow("Production", formatEnergy(device.currentValue("production")), "green")
    energyTile += tileRow("Consumption", formatEnergy(device.currentValue("consumption")), device.currentValue("production") < device.currentValue("consumption") ? "red" : "green")
    energyTile += tileRow("Self Consumed", formatEnergy(device.currentValue("self_consumption")), "blue")
    energyTile += tileRow("Import", formatEnergy(device.currentValue("purchased")), device.currentValue("feed_in") < device.currentValue("purchased") ? "red" : "green")
    energyTile += tileRow("Exported", formatEnergy(device.currentValue("feed_in")), "green")
    if (settings.display_last_updated) energyTile += "<tr><td colspan='2' style='text-align:center;font-size:11px'>Updated: ${state.last_updated}${staleNote}</td></tr>"
    energyTile += attributionRow()
    energyTile += "</table></div>"
    emitTile("energy_tile", energyTile)

    String overviewTile = "<div style='font-size:13px'><table width='100%'>"
    if (settings.overview_title) overviewTile += "<tr><td colspan='2' style='text-align:center'>${settings.overview_title}</td></tr>"
    overviewTile += tileRow("Today", formatEnergy(device.currentValue("last_day")), "green")
    overviewTile += tileRow("Monthly", formatEnergy(device.currentValue("last_month")), "green")
    overviewTile += tileRow("Yearly", formatEnergy(device.currentValue("last_year")), "green")
    overviewTile += tileRow("Lifetime", formatEnergy(device.currentValue("lifetime")), "green")
    if (settings.display_last_updated) overviewTile += "<tr><td colspan='2' style='text-align:center;font-size:11px'>Updated: ${state.last_updated}${staleNote}</td></tr>"
    overviewTile += attributionRow()
    overviewTile += "</table></div>"
    emitTile("overview_tile", overviewTile)

    String flowTile = "<div style='font-size:13px'><table width='100%'>"
    if (settings.power_flow_title) flowTile += "<tr><td colspan='2' style='text-align:center'>${settings.power_flow_title}</td></tr>"
    flowTile += tileRow("Solar", formatPower(device.currentValue("pv_power")), "green")
    flowTile += tileRow("Grid", formatPower(device.currentValue("grid_power")), state.flow_direction ?: "gray")
    if (device.currentValue("storage_power") != null) {
        String battColor = device.currentValue("storage_direction") == "charging" ? "orange" : "green"
        flowTile += tileRow("Battery", formatPower(device.currentValue("storage_power")), battColor)
        flowTile += tileRow("SoC", "${device.currentValue("battery") ?: "—"}%", "blue")
    }
    flowTile += tileRow("Usage", formatPower(device.currentValue("load_power")), "red")
    if (settings.display_last_updated) flowTile += "<tr><td colspan='2' style='text-align:center;font-size:11px'>Updated: ${state.last_updated}${staleNote}</td></tr>"
    flowTile += attributionRow()
    flowTile += "</table></div>"
    emitTile("power_flow_tile", flowTile)
}

// --- Quota ledger ---

private void initializeState() {
    if (!atomicState.quotaDate) atomicState.quotaDate = siteToday()
    if (atomicState.quotaUsed == null) atomicState.quotaUsed = 0
    if (atomicState.backoffUntil == null) atomicState.backoffUntil = 0L
    if (atomicState.authHalted == null) atomicState.authHalted = false
    if (atomicState.wasSolarDay == null) atomicState.wasSolarDay = true
}

private void resetQuotaIfNewDay() {
    String today = siteToday()
    if (atomicState.quotaDate != today) {
        atomicState.quotaDate = today
        atomicState.quotaUsed = 0
        atomicState.backoffUntil = 0L
        atomicState.failures = 0
        atomicState.nightEnergyDone = false
        if (dbg()) log.debug "Quota counter reset for site day ${today}"
    }
}

private boolean spendCall(String tag) {
    resetQuotaIfNewDay()
    int budget = Math.min(280, (settings.daily_budget ?: 150) as int)
    int used = (atomicState.quotaUsed ?: 0) as int
    if (used >= budget) {
        setApiStatus("quota_guard")
        if (dbg()) log.debug "${tag}: daily budget exhausted (${used}/${budget})"
        return false
    }
    long backoffUntil = (atomicState.backoffUntil ?: 0L) as long
    if (now() < backoffUntil) {
        if (dbg()) log.debug "${tag}: in backoff until ${new Date(backoffUntil)}"
        return false
    }
    atomicState.quotaUsed = used + 1
    emitApiCallsToday()
    if (dbg()) log.debug "${tag}: spending call ${used + 1}/${budget}"
    return true
}

private Map resolveIntervals() {
    switch (settings.polling_intensity) {
        case "Low":
            return [energy: 60, overview: 60, powerFlow: 30]
        case "High":
            return [energy: 15, overview: 15, powerFlow: 10]
        case "Custom":
            return [
                energy: (settings.energy_interval_minutes ?: 30) as int,
                overview: (settings.overview_interval_minutes ?: 30) as int,
                powerFlow: (settings.power_flow_interval_minutes ?: 15) as int
            ]
        default:
            return [energy: 30, overview: 30, powerFlow: 15]
    }
}

private boolean isEndpointDue(String endpoint, int intervalMinutes) {
    String key = "lastPoll_${endpoint}"
    Long last = atomicState[key] as Long
    if (!last) return true
    long elapsedMin = (now() - last) / 60000L
    return elapsedMin >= intervalMinutes
}

private void markEndpointPolled(String endpoint) {
    atomicState["lastPoll_${endpoint}"] = now()
}

// --- Error handling ---

private void onFailure(String tag, Exception e) {
    Integer statusCode = null
    try { statusCode = e.getStatusCode() } catch (ignored) { }

    String msg = redact(e.message)
    switch (statusCode) {
        case 401:
        case 403:
            log.error "${tag}: auth rejected (${statusCode}) — polling halted until settings are saved."
            atomicState.authHalted = true
            unschedule()
            setApiStatus("auth_failed")
            break
        case 429:
            int failures = ((atomicState.failures ?: 0) as int) + 1
            atomicState.failures = failures
            long mins = Math.min(480L, 15L * (1L << Math.min(5, failures - 1)))
            if (msg?.toLowerCase()?.contains("daily limit") || msg?.toLowerCase()?.contains("quota")) {
                atomicState.backoffUntil = endOfSiteDayMs()
                log.warn "${tag}: daily quota exceeded — halted until site midnight. Calls today: ${atomicState.quotaUsed}"
            } else {
                atomicState.backoffUntil = now() + (mins * 60000L)
                log.warn "${tag}: 429 rate limited — backing off ${mins} min. Calls today: ${atomicState.quotaUsed}"
            }
            setApiStatus("rate_limited")
            break
        default:
            log.warn "${tag}: transient failure, holding last-good values — ${msg ?: 'unknown error'}"
            setApiStatus("degraded")
    }
}

private void markSuccess() {
    atomicState.lastGoodPoll = now()
    atomicState.failures = 0
    setApiStatus("ok")
    updateDataAge()
}

private String redact(String text) {
    if (!text) return text
    return text.replaceAll(/(?i)api_key=[^&\s]+/, "api_key=REDACTED")
}

// --- Emit helpers ---

private void emitNumber(String name, def raw, BigDecimal deadband, String unit) {
    if (raw == null) return
    BigDecimal value = raw as BigDecimal
    def previous = device.currentValue(name)
    if (previous != null) {
        try {
            if (((previous as BigDecimal) - value).abs() < deadband) return
        } catch (ignored) { }
    }
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    sendEvent(evt)
}

private void emitString(String name, String value) {
    if (value == null) return
    if (device.currentValue(name) == value) return
    sendEvent(name: name, value: value)
}

private void emitCumulative(String name, def raw, String unit) {
    if (raw == null) return
    BigDecimal value = raw as BigDecimal
    if (name == "lifetime") {
        def previous = device.currentValue("lifetime")
        if (previous != null && value < (previous as BigDecimal)) {
            if (dbg()) log.debug "lifetime would decrease (${previous} -> ${value}), preserving last-good"
            return
        }
    }
    def previous = device.currentValue(name)
    if (previous != null && previous == value) return
    sendEvent(name: name, value: value, unit: unit)
}

private void emitTile(String name, String html) {
    if (html.length() > 1024) {
        log.warn "Tile ${name} exceeds 1024 characters (${html.length()}), truncating."
        html = html.take(1020) + "..."
    }
    sendEvent(name: name, value: html)
}

private void emitApiCallsToday() {
    int used = (atomicState.quotaUsed ?: 0) as int
    if (device.currentValue("apiCallsToday") != used) {
        sendEvent(name: "apiCallsToday", value: used)
    }
}

private void setApiStatus(String status) {
    if (device.currentValue("apiStatus") != status) {
        sendEvent(name: "apiStatus", value: status)
    }
}

private void updateDataAge() {
    Long last = atomicState.lastGoodPoll as Long
    if (!last) return
    long ageSec = (now() - last) / 1000L
    if (device.currentValue("dataAge") != ageSec) {
        sendEvent(name: "dataAge", value: ageSec, unit: "s")
    }
}

private boolean isDataStale() {
    Long last = atomicState.lastGoodPoll as Long
    if (!last) return true
    Map intervals = resolveIntervals()
    long maxIntervalMs = Math.max(intervals.energy, Math.max(intervals.overview, intervals.powerFlow)) * 60000L * 2
    return (now() - last) > maxIntervalMs
}

// --- Unit and time helpers ---

private BigDecimal toWatts(def raw, String unit) {
    if (raw == null) return null
    BigDecimal value = raw as BigDecimal
    switch (unit?.toString()?.toUpperCase()) {
        case "W":
            return value
        case "KW":
            return value * 1000
        case "MW":
            return value * 1000000
        default:
            if (unit) log.warn "Unrecognized power unit '${unit}' — assuming W"
            return value
    }
}

private TimeZone siteTimeZone() {
    if (settings.site_timezone?.trim()) {
        return TimeZone.getTimeZone(settings.site_timezone.trim())
    }
    return location?.timeZone ?: TimeZone.getDefault()
}

private String siteToday() {
    return new Date().format("yyyy-MM-dd", siteTimeZone())
}

private long endOfSiteDayMs() {
    def cal = Calendar.getInstance(siteTimeZone())
    cal.set(Calendar.HOUR_OF_DAY, 23)
    cal.set(Calendar.MINUTE, 59)
    cal.set(Calendar.SECOND, 59)
    cal.set(Calendar.MILLISECOND, 999)
    return cal.timeInMillis
}

private boolean isSolarDay() {
    def sunrise = location?.sunrise
    def sunset = location?.sunset
    if (!sunrise || !sunset) return true
    def nowDate = new Date()
    return nowDate >= sunrise && nowDate <= sunset
}

private boolean dbg() {
    return settings.debug && state.debugLogging != false
}

// --- Formatting ---

private String tileRow(String label, String value, String color) {
    return "<tr><td>${label}:</td><td><span style='color:${color}'>${value ?: "—"}</span></td></tr>"
}

private String attributionRow() {
    return "<tr><td colspan='2' style='text-align:center;font-size:10px;padding-top:4px'><a href='https://www.solaredge.com' target='_blank'>SolarEdge</a></td></tr>"
}

private String formatEnergy(energy) {
    if (energy == null) return "—"
    double e = energy as double
    if (e < 1000) return "${Math.round(e)} Wh"
    if (e < 1000000) return "${Math.round((e / 1000) * 100) / 100} kWh"
    return "${Math.round((e / 1000000) * 100) / 100} MWh"
}

private String formatPower(Number watts) {
    if (watts == null) return "—"
    double w = watts as double
    double absW = Math.abs(w)
    String sign = w < 0 ? "-" : ""
    if (absW >= 1000) return "${sign}${Math.round((absW / 1000) * 100) / 100} kW"
    return "${sign}${Math.round(absW)} W"
}
