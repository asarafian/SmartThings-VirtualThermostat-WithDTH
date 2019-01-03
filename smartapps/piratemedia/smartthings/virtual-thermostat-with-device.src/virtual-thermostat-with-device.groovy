definition(
    name: "Virtual Thermostat With Device",
    namespace: "piratemedia/smartthings",
    author: "Eliot S.",
    description: "Control a heater in conjunction with any temperature sensor, like a SmartSense Multi.",
    category: "Green Living",
    iconUrl: "https://raw.githubusercontent.com/eliotstocker/SmartThings-VirtualThermostat-WithDTH/master/logo-small.png",
    iconX2Url: "https://raw.githubusercontent.com/eliotstocker/SmartThings-VirtualThermostat-WithDTH/master/logo.png",
	parent: "piratemedia/smartthings:Virtual Thermostat Manager",
)

preferences {
	page(name:"Settings", title:"Settings", uninstall:true, install:true ) {
        section("Choose a temperature sensor(s)... (If multiple sensors are selected, the average value will be used)"){
            input "sensors", "capability.temperatureMeasurement", title: "Sensor", multiple: true
        }
        section("Select the heater outlet(s)... "){
            input "outlets", "capability.switch", title: "Outlets", multiple: true
        }
        section("Only heat when contact isnt open (optional, leave blank to not require contact sensor)..."){
            input "motion", "capability.contactSensor", title: "Contact", required: false
        }
        section ("Scheduled Setpoints", hideable: true, hidden: true) {
            input (name:"numscheduled", title: "Number of scheduled setpoints", type: "number", refreshAfterSelection: true)
            href(title: "Schedule Setpoints", page: "ScheduledChanges")
        }
        section("Never go below this temperature: (optional)"){
            input "emergencySetpoint", "decimal", title: "Emergency Temp", required: false
        }
        section("Temperature Threshold (Don't allow heating to go above or below this amount from set temperature)") {
            input "threshold", "decimal", "title": "Temperature Threshold", required: false, defaultValue: 1.0
        }
	}
    page(name: "ScheduledChanges")
}

def ScheduledChanges() {
    dynamicPage(name: "ScheduledChanges", uninstall: true, install: false) {
        for (int i = 1; i <= settings.numscheduled; i++) {
            section("Scheduled Setpoint $i") {
                input "scheduleTime${i}", "time", title: "At this time:", required: true
                input "scheduleSetpoint${i}", "decimal", title: "Set this temperature:", required: true
            }
        }
    }
}

def installed()
{
    log.debug "running installed"
    state.deviceID = Math.abs(new Random().nextInt() % 9999) + 1
	state.lastTemp = null
    state.contact = true
    state.todayTime = 0
    state.yesterdayTime = 0
    state.date = new Date().format("dd-MM-yy")
    state.lastOn = 0
    /*def thermostat = createDevice()
    
	subscribe(sensor, "temperature", temperatureHandler)
	if (motion) {
		subscribe(motion, "motion", motionHandler)
	}
    
    subscribe(thermostat, "thermostatSetpoint", thermostatTemperatureHandler)
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    thermostat.setVirtualTemperature(sensor.currentValue("temperature"))*/
}

def createDevice() {
    def thermostat
    def label = app.getLabel()
	// Commenting out hub refernce - breaks in several thermostat DHs
    log.debug "create device with id: pmvt$state.deviceID, named: $label" //, hub: $sensor.hub.id"
    try {
        thermostat = addChildDevice("piratemedia/smartthings", "Virtual Thermostat Device", "pmvt" + state.deviceID, null, [label: label, name: label, completedSetup: true])
    } catch(e) {
        log.error("caught exception", e)
    }
    return thermostat
}

def getThermostat() {
	def child = getChildDevices().find {
    	d -> d.deviceNetworkId.startsWith("pmvt" + state.deviceID)
  	}
    return child
}

def uninstalled() {
    deleteChildDevice("pmvt" + state.deviceID)
}

def updated()
{
    log.debug "running updated: $app.label"
	unsubscribe()
    unschedule()
    def thermostat = getThermostat()
    if(thermostat == null) {
        thermostat = createDevice()
    }
    state.contact = true
	state.lastTemp = null
    if(state.todayTime == null) state.todayTime = 0
    if(state.yesterdayTime == null) state.yesterdayTime = 0
    if(state.date == null) state.date = new Date().format("dd-MM-yy")
    if(state.lastOn == null) state.lastOn = 0
	subscribe(sensors, "temperature", temperatureHandler)
	if (motion) {
		subscribe(motion, "contact", motionHandler)
	}
    subscribe(thermostat, "thermostatSetpoint", thermostatTemperatureHandler)
    subscribe(thermostat, "thermostatMode", thermostatModeHandler)
    thermostat.clearSensorData()
    thermostat.setVirtualTemperature(getAverageTemperature())
	thermostat.setTemperatureScale(parent.getTempScale())
    runEvery5Minutes(updateSetpointBySchedule)
    runEvery1Hour(updateTimings)
}

def getAverageTemperature() {
	def total = 0;
    def count = 0;
	for(sensor in sensors) {
    	total += sensor.currentValue("temperature")
        thermostat.setIndividualTemperature(sensor.currentValue("temperature"), count, sensor.label)
        count++
    }
    return total / count
}

def temperatureHandler(evt)
{
    def thermostat = getThermostat()
    thermostat.setVirtualTemperature(getAverageTemperature())
	if (state.contact || emergencySetpoint) {
		evaluate(evt.doubleValue, thermostat.currentValue("thermostatSetpoint"))
        state.lastTemp = evt.doubleValue
	}
	else {
		heatingOff()
	}
}

def motionHandler(evt)
{
    def thermostat = getThermostat()
	if (evt.value == "closed") {
    	state.contact = true
		def thisTemp = getAverageTemperature()
		if (thisTemp != null) {
			evaluate(thisTemp, thermostat.currentValue("thermostatSetpoint"))
			state.lastTemp = thisTemp
		}
	} else if (evt.value == "open") {
        log.debug "should turn heating off"
    	state.contact = false
	    heatingOff()
	}
}

def thermostatTemperatureHandler(evt) {
	def temperature = evt.doubleValue
    //setpoint = temperature
	log.debug "Desired Temperature set to: $temperature $state.contact"
    
    def thisTemp = getAverageTemperature()
	if (state.contact) {
		evaluate(thisTemp, temperature)
	}
	else {
		heatingOff()
	}
}

def thermostatModeHandler(evt) {
	def mode = evt.value
	log.debug "Mode Changed to: $mode"
    def thermostat = getThermostat()
    
    def thisTemp = getAverageTemperature()
	if (state.contact) {
		evaluate(thisTemp, thermostat.currentValue("thermostatSetpoint"))
	}
	else {
		heatingOff(mode == 'heat' ? false : true)
	}
}

private evaluate(currentTemp, desiredTemp)
{
	log.debug "EVALUATE($currentTemp, $desiredTemp)"
	// heater
	if ( (desiredTemp - currentTemp >= threshold)) {
		heatingOn()
	} else if ( (currentTemp - desiredTemp >= threshold)) {
		heatingOff()
	} else if(state.current == "on") {
        updateTimings()
    }
}

def updateSetpointBySchedule() {
    log.debug "Update setpoint based on schedule."
	def thermostat = getThermostat()
	def timeNow = now()
    def newSetpoint = null
   
	for (int i = 1; i <= settings.numscheduled; i++) {
    	def scheduledTime = timeToday(settings["scheduleTime$i"], location.timeZone)
 		if (scheduledTime != null) {
	    	if (scheduledTime.time <= timeNow) {
    	        newSetpoint = settings["scheduleSetpoint$i"]
			}
		}
	}
    if (newSetpoint != null) {
		log.debug "Changing setpoint to $newSetpoint based on schedule."
    	thermostat.setHeatingSetpoint(newSetpoint.toString())
    }
}

def heatingOn() {
    if(thermostat.currentValue('thermostatMode') == 'heat' || force) {
    	log.debug "Heating on Now"
        outletsOn()
        thermostat.setHeatingStatus(true)
    } else {
        heatingOff(true)
    }
}

def heatingOff(heatingOff) {
	def thisTemp = getAverageTemperature()
    if (thisTemp <= emergencySetpoint) {
        log.debug "Heating in Emergency Mode Now"
        ouletsOn()
        thermostat.setEmergencyMode(true)
    } else {
    	log.debug "Heating off Now"
    	outletsOff()
		if(heatingOff) {
			thermostat.setHeatingOff(true)
		} else {
			thermostat.setHeatingStatus(false)
		}
    }
}

def updateTempScale() {
	thermostat.setTemperatureScale(parent.getTempScale())
}

def updateTimings() {
    def date = new Date().format("dd-MM-yy")
    if(state.current == "on") {
        int time = Math.round(new Date().getTime() / 1000) - state.lastOn
        state.todayTime = state.todayTime + time
        state.lastOn = Math.round(new Date().getTime() / 1000)
    }
    if(state.date != date) {
        state.yesterdayTime = state.todayTime
        state.date = date
        state.todayTime = 0
    }
    thermostat.setTimings(state.todayTime, state.yesterdayTime)
}

def outletsOn() {
    outlets.on()
    def date = new Date().format("dd-MM-yy")
    if(state.current == "on") {
        int time = Math.round(new Date().getTime() / 1000) - state.lastOn
        state.todayTime = state.todayTime + time
    }
    if(state.date != date) {
        state.yesterdayTime = state.todayTime
        state.date = date
        state.todayTime = 0
    }
    state.lastOn = Math.round(new Date().getTime() / 1000)
    state.current = "on"
    thermostat.setTimings(state.todayTime, state.yesterdayTime)
}

def outletsOff() {
    outlets.off()
    def date = new Date().format("dd-MM-yy")
    if(state.current == "on") {
        int time = Math.round(new Date().getTime() / 1000) - state.lastOn
        state.todayTime = state.todayTime + time
    }
    if(state.date != date) {
        state.yesterdayTime = state.todayTime
        state.date = date
        state.todayTime = 0
    }
    state.current = "off"
    state.lastOn = 0;
    thermostat.setTimings(state.todayTime, state.yesterdayTime)
}