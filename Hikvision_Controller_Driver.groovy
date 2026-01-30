/**
 *  HikVision Driver - 
 *
 *  Copyright 2024 VH
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
  *
 *
*  HikVision Driver for Hubitat - ChangeLog
*
*  VH - 2024
* Prereq - Install Driver + Enable Hikvision Monitor to send alarms to Hubitat IP address and port 39501.
*
* ver. 1.0.0 2024-10-06 hhorigian - Initial release: Open Door, Close Door, Keep Door Open, Restart.
* ver. 1.0.1 2024-10-09 hhorigian - Addded PARSE to receive feedback from HikVision device in network. Added LASTUSERIN attribute coming from Facial/Card/Biometrial
* to be used in RM for anything depending on the user that was allowed in the Hikvision device.  Added some more LOGS depending on the type of event in door. Door status now
* is reported and upted from hikvision feedback.
*
* ver. 1.0.2 2026-01-06 - Added Lock capability + AcsWorkStatus refresh/poll + Users list + Last access search (DS-K1T343MWX)
*
 */

metadata {
  definition (name: "HikVision Controller Driver", namespace: "VH", author: "VH", vid: "generic-contact") {
    capability "Contact Sensor"
    capability "Sensor"
    capability "Switch"
    capability "Lock"
    capability "Refresh"
    capability "Actuator"
  }

  preferences {
        input name: "debugOutput", type: "bool", title: "Enable Degug  Log", defaultValue: false
        input name: "ipdomodulo", type: "string", title: "Module IP", defaultValue: ""
        input name: "deviceusername", type: "text", title: "HikVision username", submitOnChange: true, required: true, defaultValue: "admin"
        input name: "devicepassword", type: "password", title: "HikVision password", submitOnChange: true, required: true, defaultValue: "admin"
        input name: "usewithhttpfeedback", type: "bool", title: "HTTP Enabled in Hikvision Device?", defaultValue: false

        // Lock/door status sync (DS-K1T343MWX)
        input name: "doorNo", type: "number", title: "Door No. (1..)", defaultValue: 1
        input name: "magneticOpenValue", type: "enum", title: "Magnetic status value that means OPEN (AcsWorkStatus.magneticStatus)", options: ["1":"1 (normally open)", "0":"0 (normally close)"], defaultValue: "1"
        input name: "pollSeconds", type: "number", title: "Polling interval in seconds (0 = disabled). Used to refresh lock/contact status from device.", defaultValue: 0

        // Users / last access
        input name: "usersMaxResults", type: "number", title: "Max users per RefreshUsers()", defaultValue: 50
        input name: "lastAccessMajorEventType", type: "number", title: "Filter majorEventType for RefreshLastAccess (optional)", required: false
        input name: "lastAccessSubEventType", type: "number", title: "Filter subEventType for RefreshLastAccess (optional)", required: false
  }
}

command "OpenDoor"
command "CloseDoor"
command "Restart"
//command "KeepDoorOpen"
//command "KeepDoorClosed"
command "GetControllerInfo"
command "RefreshUsers"
command "RefreshLastAccess"

attribute "DoorStatus", "string"
attribute "DoorLockStatusRaw", "string"
attribute "MagneticStatusRaw", "string"

attribute "LastUserIn", "string"
attribute "LastUserInEmployeeNo", "string"
attribute "lastCodeName", "string"
attribute "LastUserInCardNo", "string"
attribute "LastAccessTime", "string"
attribute "LastAccessResult", "string"

attribute "UsersJson", "string"
attribute "UsersJsonRaw", "string"
attribute "LastAccessRaw", "string"

attribute "door", "string"        // open / closed (status físico)
attribute "doorSensor", "string"  // open / closed (opcional/extra)
attribute "doorLastUpdate", "string"

attribute "model", "string"  
attribute "firmware", "string"  
attribute "serialnumber", "string"  

def initialize() {
    state.currentip = ""
    startPolling()
}


def installed()
{
    log.debug "installed()"
    log.warn "Installing new HikVision Controller"
    log.info "Setting device Name to Label: " + device.getLabel()
    device.setName(device.getLabel())
    sendEvent(name:"Driver",value:"Please read the User Guide before adding your first camera HikVision Controller")
    initialize()

}


def updated()
{

    log.debug "Updated()"
    cname = device.getLabel()
    cname = cname.toUpperCase()
    log.warn "Saving Preferences for " + cname
    state.clear()
    device.removeDataValue("Name")   
    device.removeDataValue("Model")
    device.removeDataValue("Firmware")
    device.removeDataValue("Serial")    
    
    

        //Get DNI 
        String dni = ""
        ipdomodulo = ipdomodulo.trim()

        device.updateSetting("ipdomodulo", [value:"${ipdomodulo}", type:"string"])
        if (GenerateDNI(ipdomodulo) == "ERR") {
            sendEvent(name:"Driver",value:"FAILED")
            return
        }
    
    //Get Info from Device
    GetControllerInfo()
    // Optional polling/refresh for Lock + Contact state
    startPolling()
    refresh()

}


def uninstalled() {
    log.debug "uninstalled()"
    unschedule()
} 


def on() {
     OpenDoor() 
}


def off() {
     CloseDoor() 
    
}


// ===== Lock capability wrappers =====
def lock() {
    // Hubitat Lock: lock = secure/closed
    CloseDoor()
    // If user does not have HTTP feedback, keep Hubitat state consistent locally
    if (usewithhttpfeedback == false) {
        sendEvent(name: "lock", value: "locked", isStateChange: true)
        sendEvent(name: "switch", value: "off", isStateChange: true)
    }
}

def unlock() {
    OpenDoor()
    if (usewithhttpfeedback == false) {
        sendEvent(name: "lock", value: "unlocked", isStateChange: true)
        sendEvent(name: "switch", value: "on", isStateChange: true)
    }
}

// ===== Refresh / Polling =====
def refresh() {
    // Read live door work status (lock + magnetic contact) directly from Hikvision
    GetAcsWorkStatus()
}

private void startPolling() {
    unschedule("pollRefresh")
    Integer s = (settings.pollSeconds ?: 0) as Integer
    if (s != null && s > 0) {
        if (s < 10) s = 10
        state._pollSeconds = s
        runIn(s, "pollRefresh")
        logDebug "Polling enabled: every ${s}s"
    } else {
        state.remove("_pollSeconds")
    }
}

def pollRefresh() {
    refresh()
    Integer s = (state._pollSeconds ?: 0) as Integer
    if (s > 0) runIn(s, "pollRefresh")
}

// ===== Read device work status (AcsWorkStatus) =====
private GetAcsWorkStatus() {
    String baseurl = "http://${settings.deviceusername}:${settings.devicepassword}@${settings.ipdomodulo}/ISAPI/AccessControl/AcsWorkStatus?format=json"
    def params = [
        uri: baseurl,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: ['Content-Type': 'application/json']
    ]
    try {
        httpGet(params) { response ->
            if (response?.status == 200 && response?.data) {
                def data = response.data
                Integer doorIndex = ((settings.doorNo ?: 1) as Integer) - 1
                if (doorIndex < 0) doorIndex = 0

                Integer dls = null
                Integer ms = null
                try { dls = (data?.AcsWorkStatus?.doorLockStatus?.getAt(doorIndex) as Integer) } catch (ignored) {}
                try { ms  = (data?.AcsWorkStatus?.magneticStatus?.getAt(doorIndex) as Integer) } catch (ignored) {}

                if (dls != null) {
                    sendEvent(name: "DoorLockStatusRaw", value: "${dls}", isStateChange: true)
                    if (dls == 0) {
                        sendEvent(name: "lock", value: "locked", isStateChange: true)
                    } else if (dls == 1) {
                        sendEvent(name: "lock", value: "unlocked", isStateChange: true)
                    }
                }

                if (ms != null) {
                    sendEvent(name: "MagneticStatusRaw", value: "${ms}", isStateChange: true)
                    String openVal = (settings.magneticOpenValue ?: "1") as String
                    boolean isOpen = ("${ms}" == openVal)

                    setDoorState(isOpen, "AcsWorkStatus")
                }

            }
        }
    } catch (Exception e) {
        logDebug "GetAcsWorkStatus failed: ${e.message}"
    }
}

// ===== Users list (for automations) =====
def RefreshUsers() {
    GetUsersList()
}

private GetUsersList() {
    // Many DS-K1T terminals support this standard ISAPI endpoint for user search.
    // If your firmware uses a different path, the debug attribute UsersJsonRaw will help us confirm.
    String baseurl = "http://${settings.deviceusername}:${settings.devicepassword}@${settings.ipdomodulo}/ISAPI/AccessControl/UserInfo/Search?format=json"
    def bodyObj = [
        UserInfoSearchCond: [
            searchID: "hubitat",
            searchResultPosition: 0,
            maxResults: (settings.usersMaxResults ?: 50)
        ]
    ]
    def params = [
        uri: baseurl,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: ['Content-Type': 'application/json'],
        body: groovy.json.JsonOutput.toJson(bodyObj)
    ]
    try {
        httpPost(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data
                // Save raw (useful for debugging / future parsing)
                sendEvent(name: "UsersJsonRaw", value: groovy.json.JsonOutput.toJson(data), isStateChange: true)

                def list = []
                def users = data?.UserInfoSearch?.UserInfo
                if (users instanceof List) {
                    users.each { u ->
                        list << [employeeNo: u?.employeeNo, name: u?.name, cardNo: u?.cardNo]
                    }
                } else if (users) {
                    list << [employeeNo: users?.employeeNo, name: users?.name, cardNo: users?.cardNo]
                }
                sendEvent(name: "UsersJson", value: groovy.json.JsonOutput.toJson(list), isStateChange: true)
                log.info "UsersJson updated (${list.size()} users)"
            } else {
                logDebug "GetUsersList response status=${resp?.status}"
            }
        }
    } catch (Exception e) {
        logDebug "GetUsersList failed: ${e.message}"
    }
}

// ===== Last access (event search) =====
def RefreshLastAccess() {
    GetLastAccessEvent()
}

private GetLastAccessEvent() {
    // Uses standard AccessControl AcsEvent search (manual shows AcsEventCond with timeReverseOrder / maxResults)
    String baseurl = "http://${settings.deviceusername}:${settings.devicepassword}@${settings.ipdomodulo}/ISAPI/AccessControl/AcsEvent?format=json"

    // Time window: last 24h (safe and small)
    def end = new Date()
    def start = new Date(end.time - (24L * 60L * 60L * 1000L))
    String fmt = "yyyy-MM-dd'T'HH:mm:ssXXX" // ISO8601 with timezone
    String startTime = start.format(fmt)
    String endTime = end.format(fmt)

    def cond = [
        searchID: "hubitatLastAccess",
        searchResultPosition: 0,
        maxResults: 1,
        timeReverseOrder: true,
        startTime: startTime,
        endTime: endTime
    ]

    // Optional filters (leave blank by default)
    if (settings.lastAccessMajorEventType) cond.majorEventType = (settings.lastAccessMajorEventType as Integer)
    if (settings.lastAccessSubEventType)   cond.subEventType  = (settings.lastAccessSubEventType as Integer)

    def bodyObj = [AcsEventCond: cond]

    def params = [
        uri: baseurl,
        contentType: "application/json",
        requestContentType: "application/json",
        headers: ['Content-Type': 'application/json'],
        body: groovy.json.JsonOutput.toJson(bodyObj)
    ]

    try {
        httpPost(params) { resp ->
            if (resp?.status == 200 && resp?.data) {
                def data = resp.data
                sendEvent(name: "LastAccessRaw", value: groovy.json.JsonOutput.toJson(data), isStateChange: true)

                def info = null
                try {
                    info = data?.AcsEvent?.InfoList?.getAt(0)
                } catch (ignored) {}

                if (info) {
                    def name = info?.name
                    def emp = info?.employeeNoString ?: info?.employeeNo
                    def card = info?.cardNo
                    def t = info?.time
                    def res = info?.attendanceStatus ?: info?.status

                    if (name != null) sendEvent(name: "LastUserIn", value: name.toString(), isStateChange: true)
                    if (name != null) sendEvent(name: "lastCodeName", value: name.toString(), isStateChange: true)
                    if (emp != null)  sendEvent(name: "LastUserInEmployeeNo", value: emp.toString(), isStateChange: true)
                    if (card != null) sendEvent(name: "LastUserInCardNo", value: card.toString(), isStateChange: true)
                    if (t != null)    sendEvent(name: "LastAccessTime", value: t.toString(), isStateChange: true)
                    if (res != null)  sendEvent(name: "LastAccessResult", value: res.toString(), isStateChange: true)

                    log.info "LastAccess updated: ${name ?: emp ?: card} @ ${t ?: ''}"
                }
            } else {
                logDebug "GetLastAccessEvent response status=${resp?.status}"
            }
        }
    } catch (Exception e) {
        logDebug "GetLastAccessEvent failed: ${e.message}"
    }
}



///// GET CONTROLLER INFO /////
/////////////////////////////
private GetControllerInfo() {
   String errcd = ""
   baseurl = "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/System/deviceInfo"
   def params = [
        uri: baseurl,
        contentType: "application/xml",
        requestContentType: "application/xml",
        headers: ['Content-Type': 'application/xml']     
    ]
    try {
        httpGet(params) { response ->
        if (response.success) {                
         xml = response.data
    }        
	}
    } catch (Exception e) {
        log.warn "Get Remote Control Info failed: ${e.message}"
    }    

    log.info "Device Type: " + xml.deviceType
    log.info "Name: " + xml.deviceName
    log.info "Model: " + xml.model
    log.info "Serial: " + xml.serialNumber
    log.info "Firmware: " + xml.firmwareVersion + " " + xml.firmwareReleasedDate
    sendEvent(name: "model", value: xml.model)
    sendEvent(name: "serial", value: xml.serialNumber)
    sendEvent(name: "firmware", value: xml.firmwareVersion)
    
    if (xml.deviceType.text() == "ACS" ) {
        strMsg = "You have connected to a Hikvision Access Controller"
        log.info strMsg
        return("ERR")
    }   
    device.updateDataValue("Device Type",xml.deviceType)
    device.updateDataValue("Name",xml.deviceName)
    device.updateDataValue("Model",xml.model)
    device.updateDataValue("Serial",xml.serialNumber)
    device.updateDataValue("Firmware",xml.firmwareVersion + " " + xml.firmwareReleasedDate)  
    return("OK")
}



///////   OpenDoor  ///////////
/////////////////////////////
def OpenDoor(){
    def xmlCode = "<RemoteControlDoor><cmd>open</cmd></RemoteControlDoor>"
    def parameterMap = [
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/AccessControl/RemoteControl/door/1",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                        body: xmlCode
                       ]  
    log.debug parameterMap  //View the construction in log

    Closure $parseResponse = { response ->            
         //log.debug response.status
         if (response.status == 200) {
                 log.info "Door Opened Manually"       

            sendEvent(name: "lock", value: "unlocked", isStateChange: true)
            sendEvent(name: "switch", value: "on", isStateChange: true)
            setDoorState(true, "cmd-open")
        
 }  
    }    
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
    
    if (usewithhttpfeedback == false) {  //If user did not enabled the HTTP Feedback in HikVision setup. 
        pauseExecution(5000)  //time for door autoclose (must match the settings in the Hikvision config for autoclose. Time in ms)
        off() // turn off-close door after time
    }      

}


///////  CloseDoor  ///////////
///////////////////////////////
def CloseDoor(){
    
    def xmlCode = "<RemoteControlDoor><cmd>close</cmd></RemoteControlDoor>"
    def parameterMap = [
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/AccessControl/RemoteControl/door/1",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                        body: xmlCode
                       ]  
     
    log.debug parameterMap  //View the construction in log
    
    Closure $parseResponse = { response ->            
         log.debug response.status
         if (response.status == 200) {
                 log.info "Door Closed Manually"  

            sendEvent(name: "lock", value: "locked", isStateChange: true)
            sendEvent(name: "switch", value: "off", isStateChange: true)
            setDoorState(false, "cmd-close")
        

         }  
        
    }    
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
}



///////  KeepDoorClosed  ///////////
///////////////////////////////////
def KeepDoorClosed(){
    
    def xmlCode = "<RemoteControlDoor><cmd>alwaysClose</cmd></RemoteControlDoor>"
    def parameterMap = [
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/AccessControl/RemoteControl/door/1",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                        body: xmlCode
                       ]  
     
    log.debug parameterMap  //View the construction in log
    
    Closure $parseResponse = { response ->            
         log.debug response.status
         if (response.status == 200) {
                 log.info "Door Keep Closed Manually"  
           
         }       
         
    }    
    
            
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
}



///////  KeepDoorOpen  ///////////
def KeepDoorOpen(){
    
    def xmlCode = "<RemoteControlDoor><cmd>alwaysOpen</cmd></RemoteControlDoor>"
    def parameterMap = [
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/AccessControl/RemoteControl/door/1",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                        body: xmlCode
                       ]  
     
    log.debug parameterMap  //View the construction in log
    
    Closure $parseResponse = { response ->            
         log.debug response.status
         if (response.status == 200) {
                 log.info "Door Keep Opened Manually"  
           
         }       
         
    }    
    
            
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
}


///////  Restart  ///////////
def Restart(){
    
    def parameterMap = [
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/System/reboot",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                       ]  
     
    log.debug parameterMap  //View the construction in log
    
    Closure $parseResponse = { response ->            
         log.debug response.status
         if (response.status == 200) {
                 log.info "HikVision Device Restarted"  
           
         }       
         
    }    
    
            
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
}


private void setDoorState(boolean isOpen, String source = null) {
    String v = isOpen ? "open" : "closed"

    sendEvent(name: "contact", value: v, isStateChange: true)

    sendEvent(name: "door", value: v, isStateChange: true)
    sendEvent(name: "doorSensor", value: v, isStateChange: true)
    sendEvent(name: "DoorStatus", value: v, isStateChange: true)

    String nowStr = new Date().format("yyyy-MM-dd HH:mm:ss")
    sendEvent(name: "doorLastUpdate", value: (source ? "${nowStr} (${source})" : nowStr), isStateChange: true)

    sendEvent(name: "Door", value: (isOpen ? "Open" : "Closed"), isStateChange: true)
}



void parse(String description) {

    def rawmsg = parseLanMessage(description)   
    def hdrs = rawmsg.headers  // its a map
    def msg = ""               // tbd
    
    if (debuga) {log.warn "***** EVENT MESSAGE RECEIVED ******"} //** v106
    //hdrs.each {log.debug it}
    msg = rawmsg.body.toString()
    
        String boundary = rawmsg?.headers?.getAt('Content-Type')?.split('boundary=')?.getAt(1)
        String subBody = rawmsg?.body?.split(boundary)?.getAt(1)
        subBody = subBody?.split("--")?.getAt(0)
        subBody = subBody?.split("Content-Disposition: form-data; name=\"event_log\"")?.getAt(1)                
        //log.debug subBody
    
    // parse the JSON 
    def resp_json = parseJson(subBody)
    log.debug resp_json
    
    MajorEventTypeid = resp_json.AccessControllerEvent.majorEventType
    subEventTypeid = resp_json.AccessControllerEvent.subEventType
    eventid = resp_json.AccessControllerEvent.frontSerialNo
    eventdate = resp_json.dateTime
    verifymode = resp_json.AccessControllerEvent.currentVerifyMode
    eventdate = new Date().format ("EEE MMM d HH:mm:ss")
  
    log.debug "Major Event Type: " + MajorEventTypeid + " ...Event dateTime: " + eventdate + " -- Verifymode = " + verifymode

    state.LastEventDate = eventdate
    state.LastEventid = eventid    
    state.subEventTypeid = subEventTypeid    
    state.MajorEventTypeid = MajorEventTypeid    
    
    if ((verifymode == "faceOrFpOrCardOrPw") || (verifymode == "cardOrFaceOrFp")) {
        lastpersonname = resp_json.AccessControllerEvent.name 
        state.NameLastUser = lastpersonname
        sendEvent(name: "LastUserIn", value: lastpersonname, isStateChange: true )
        try {
            def emp = resp_json.AccessControllerEvent.employeeNo
            if (emp != null) sendEvent(name: "LastUserInEmployeeNo", value: emp.toString(), isStateChange: true )
        } catch (ignored) {}
        if (subEventTypeid == 75) {
            sendEvent(name: "EntryMethod", value: "Authenticated via Card/Face" )
        }
    } 
    
    if (subEventTypeid == 21) {
        sendEvent(name: "switch", value: "on", isStateChange: true)
        sendEvent(name: "lock", value: "unlocked", isStateChange: true)
        setDoorState(true, "event-21")
        log.info "Door Open from api"
    }

    if (subEventTypeid == 22) {
        sendEvent(name: "switch", value: "off", isStateChange: true)
        sendEvent(name: "lock", value: "locked", isStateChange: true)
        setDoorState(false, "event-22")
        log.info "Door Closed from api"
    }

    
    if (subEventTypeid == 1024) {
                 log.info "** Remote Unlock ** "     
    }     
        
    
    //subEventTypeid 75 - Authenticated via Face
    //subEventTypeid 21 - Door Unlockeda
    //subEventTypeid 22 - Door Unlockeda
    //subEventTypeid 1024 - Remote Unlock    
    
        
   
}
	
// GENERATE DNI - GENERATE DNI - GENERATE DNI - GENERATE DNI
private GenerateDNI(String ip) {
    String dni = ip.tokenize(".").collect {String.format( "%02x", it.toInteger() ) }.join()
    dni = dni.toUpperCase()
    try {device.deviceNetworkId = "${dni}"
    } catch (Exception e) {
        log.error e.message
        return("ERR")
    }
    return(dni)
}



//DEBUG
private logDebug(msg) {
  if (settings?.debugOutput || settings?.debugOutput == null) {
    log.debug "$msg"
  }
}
