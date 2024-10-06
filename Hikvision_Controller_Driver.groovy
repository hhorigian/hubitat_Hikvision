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
*
* ver. 1.0.0 2024-10-06 hhorigian - Initial release: Open Door, Close Door, Keep Door Open, Restart.  
*
*        TODO: One Function for all door commands: open/close/keepopen/keepclosed
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
      
  }   
  
command "OpenDoor"
command "CloseDoor"
command "Restart"
command "KeepDoorOpen"
command "KeepDoorClosed"

attribute "DoorStatus", "string"


def initialized()
{
    state.currentip = ""
    log.debug "initialized()"
}


def installed()
{
    log.debug "installed()"
}


def update()
{
    log.debug "Updated()"
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


/*def AtualizaIP(ipADD,deviceuser,devicepwd) {
    state.currentip = ipADD
    state.deviceuser = deviceuser
    state.devicepwd = devicepwd
    ipdomodulo  = state.currentip
    device.updateSetting("ipdomodulo", [value:"${ipADD}", type:"string"])
    log.info "Device with IP updated " + state.currentip
    
}*/


///////   OpenDoor  ///////////
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
         log.debug response.status
         if (response.status == 200) {
                 sendEvent(name: "switch", value: "on", isStateChange: true)
                 sendEvent(name: "Door", value: "Open", isStateChange: true)
                 log.info "Door Opened"
         }         
    } 
        
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  } 
    
    pauseExecution(5000)  //time for door autoclose (must match the settings in the Hikvision config for autoclose. Time in ms)   
    off() // turn off-close door after time
    
}


///////  CloseDoor  ///////////
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
                 sendEvent(name: "switch", value: "off", isStateChange: true)
                 sendEvent(name: "Door", value: "Closed", isStateChange: true)   
                 log.info "Door Closed"
             
             
         }         

    }
    
    
    
        try { httpPut(parameterMap,$parseResponse) 
        
            }            
        catch (e) {
            log.debug "Error Response : ${e.message}" 
                  }       
}



///////  KeepDoorClosed  ///////////
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
                 sendEvent(name: "switch", value: "off", isStateChange: true)
                 sendEvent(name: "Door", value: "Closed", isStateChange: true)   
                 log.info "Door Keep Closed"
             
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
                 sendEvent(name: "switch", value: "on", isStateChange: true)
                 sendEvent(name: "Door", value: "Open", isStateChange: true)
                 log.info "Door Keep Opened"
             
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


//DEBUG
private logDebug(msg) {
  if (settings?.debugOutput || settings?.debugOutput == null) {
    log.debug "$msg"
  }
}

