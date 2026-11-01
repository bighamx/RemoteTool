package com.chuckiehelper.mobile.nativeui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Write-operation feedback belongs to a request, not a background list-refresh epoch. */
internal class WorkspaceActionFeedback {
    data class Ticket(val workspace:String,val serial:Long)
    private var serial=0L
    private val current=mutableMapOf<String,Ticket>()
    var busy by mutableStateOf<Set<String>>(emptySet());private set
    var errors by mutableStateOf<Map<String,String>>(emptyMap());private set
    var notices by mutableStateOf<Map<String,String>>(emptyMap());private set
    fun begin(id:String):Ticket? {
        if(id in current)return null
        val ticket=Ticket(id,++serial);current[id]=ticket;busy=busy+id;errors=errors-id;notices=notices-id;return ticket
    }
    fun fail(ticket:Ticket,message:String){if(current[ticket.workspace]==ticket)errors=errors+(ticket.workspace to message)}
    fun succeed(ticket:Ticket,message:String){if(current[ticket.workspace]==ticket)notices=notices+(ticket.workspace to message)}
    fun finish(ticket:Ticket){if(current[ticket.workspace]==ticket){current.remove(ticket.workspace);busy=busy-ticket.workspace}}
    fun dismiss(id:String){errors=errors-id;notices=notices-id}
    fun clear(){serial++;current.clear();busy=emptySet();errors=emptyMap();notices=emptyMap()}
}
