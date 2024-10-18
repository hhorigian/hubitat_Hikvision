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
*
*       
 */

metadata {
  definition (name: "HikVision Controller Driver", namespace: "VH", author: "VH", vid: "generic-contact") {
    capability "Contact Sensor"
    capability "Sensor"
    capability "Switch"  
  }
      
  }

  import groovy.transform.Field

  preferences {
        input name: "debugOutput", type: "bool", title: "Enable Degug  Log", defaultValue: false
        input name: "ipdomodulo", type: "string", title: "Module IP", defaultValue: ""     
        input name: "deviceusername", type: "text", title: "HikVision username", submitOnChange: true, required: true, defaultValue: "admin"             
        input name: "devicepassword", type: "password", title: "HikVision password", submitOnChange: true, required: true, defaultValue: "admin"                      
        input name: "usewithhttpfeedback", type: "bool", title: "HTTP Enabled in Hikvision Device?", defaultValue: false              
      
  }   
  
command "OpenDoor"
command "CloseDoor"
command "Restart"
command "KeepDoorOpen"
command "KeepDoorClosed"

attribute "DoorStatus", "string"
attribute "LastUserIn", "string"


def initialized()
{
    state.currentip = ""
    log.debug "initialized()"
}


def installed()
{
    log.debug "installed()"
    log.warn "Installing new HikVision Controller"
    log.info "Setting device Name to Label: " + device.getLabel()
    device.setName(device.getLabel())
    sendEvent(name:"Driver",value:"Please read the User Guide before adding your first camera HikVision Controller")
    
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
                // sendEvent(name: "switch", value: "on", isStateChange: true)
                // sendEvent(name: "Door", value: "Open", isStateChange: true)
                 log.info "Door Opened Manually"
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
                 //sendEvent(name: "switch", value: "off", isStateChange: true)
                 //sendEvent(name: "Door", value: "Closed", isStateChange: true)   
                 log.info "Door Closed Manually"
             
             
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
                 //sendEvent(name: "switch", value: "off", isStateChange: true)
                 //sendEvent(name: "Door", value: "Closed", isStateChange: true)   
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
                 //sendEvent(name: "switch", value: "on", isStateChange: true)
                 //sendEvent(name: "Door", value: "Open", isStateChange: true)
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
    
    //def xmlCode = ""
    def parameterMap = [ 
                        uri: "http://" + settings.deviceusername + ":" + settings.devicepassword + "@" + settings.ipdomodulo + "/ISAPI/System/reboot",
                        requestContentType: "application/xml",
                        contentType:"application/xml",
                        headers: ['Content-Type': 'application/xml'],
                        //body: xmlCode
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
  
    log.debug "Major Event Type: " + MajorEventTypeid + " -- Major sub Event Type: " + subEventTypeid + " -- Event ID: " + eventid + " -- Event dateTime: " + eventdate + " -- Verifymode = " + verifymode

    state.LastEventDate = eventdate
    state.LastEventid = eventid    
    state.subEventTypeid = subEventTypeid    
    state.MajorEventTypeid = MajorEventTypeid    
    
    if ((verifymode == "faceOrFpOrCardOrPw") || (verifymode == "cardOrFaceOrFp")) {
        lastpersonname = resp_json.AccessControllerEvent.name 
        state.NameLastUser = lastpersonname
        sendEvent(name: "LastUserIn", value: lastpersonname, isStateChange: true )
        if (subEventTypeid == 75) {
            sendEvent(name: "EntryMethod", value: "Authenticated via Card/Face" )
        }
    } 

    if (subEventTypeid == 21) {
                 sendEvent(name: "switch", value: "on", isStateChange: true)
                 sendEvent(name: "Door", value: "Open", isStateChange: true)
                 log.info "Door Open from api"     
    } 
    
    if (subEventTypeid == 22) {
                 sendEvent(name: "switch", value: "off", isStateChange: true)
                 sendEvent(name: "Door", value: "Closed", isStateChange: true)
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




