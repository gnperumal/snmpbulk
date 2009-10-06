/*
 * PollWorker.scala
 *
 * To change this template, choose Tools | Template Manager
 * and open the template in the editor.
 */

package scalasc
import org.snmp4j.smi.Address
import org.snmp4j.TimeoutModel
import org.snmp4j.smi._
import org.snmp4j.transport.DefaultUdpTransportMapping
import org.snmp4j.util.DefaultPDUFactory
import org.snmp4j.util.PDUFactory
import org.snmp4j.util.TableEvent
import org.snmp4j.util.TableUtils
import org.snmp4j.CommunityTarget
import org.snmp4j.Snmp
import org.snmp4j.PDU
import org.snmp4j.TransportMapping
import org.snmp4j.mp._
import org.snmp4j.event._
import java.lang.Thread
import org.snmp4j.util._
import java.util.concurrent.ConcurrentHashMap

//import scala.collection.jcl._

class PollWorker extends Thread {


    class PollTarget(_ip:String, colBulk:Array[OID], colSing:Array[OID]) {
        var _dataBulk = new Array[scala.collection.mutable.LinkedHashMap[Int,VariableBinding]](colBulk.length)
        var _dataSing = new Array[VariableBinding](colSing.length)
        var completion=colBulk.length + colSing.length
        for( i <- 0 to colBulk.length-1) { _dataBulk(i) = new scala.collection.mutable.LinkedHashMap[Int,VariableBinding]() }
        val targetAddress = GenericAddress.parse("udp:" + _ip + "/161")
        val target = new CommunityTarget(targetAddress, new OctetString("public"))
        target.setVersion(SnmpConstants.version2c)
        def dataBulk() = _dataBulk
        def dataSing() = _dataSing
        def doCompletion() : Unit = {
            completion -= 1
            if ( completion== 0) {
                for( i <- 0 to colSing.length-1) {
                    print("oid: " + colSing(i) + "=")
                    printVariable(0,_dataSing(i).getVariable)
                    _dataSing(i)=null
                }
                for(i <- 0 to colBulk.length-1){
                    println("oid: " + colBulk(i))
                    for(i_oid <- _dataBulk(i).keys ) {
                        val v = _dataBulk(i)(i_oid).getVariable()
                        printVariable(i_oid, v)
                    }
                    _dataBulk(i).clear
                }
            }
        }
        def printVariable(i:int, v:Variable):Unit =
        {
            print("oid: " + i + " var: ")
            v match {
                case vx:TimeTicks => print(" timeticks \"" + vx.toString() + "\" ")
                case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                case _ => print (" " + v + " ")
            }
            println()
        }
/*
                println("this oid: " + oid + " base oid: " + out.baseOid)
                if ( oid.startsWith(out.baseOid) ) {
                    print("i: " + i + "{ ")
                    v match {
                        case vx:TimeTicks => print(" timeticks \"" + vx.toString() + "\" ")
                        case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                        case _ => print (" " + v + " ")
                    }
                    println(" } ")
                } else {
            }
*/
        
        

        def ip() = _ip
        var currPDU = new PDU()

        def sendInitialPDU(snmp:Snmp, listenerBulk:ResponseListener, listenerSing:ResponseListener) : Unit = {
            var index=0
            for ( oid <- colBulk) {
                currPDU = new PDU()
                currPDU.add(new VariableBinding(oid))
                currPDU.setMaxRepetitions(5)
                currPDU.setType(PDU.GETBULK)
                snmp.sendPDU(currPDU, target, null, listenerBulk)
                outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,oid,this, index, -1))
                println("sent bulk " + currPDU.getRequestID.toInt + " for " + oid)
                index += 1
            }
            index=0
            for ( oid <- colSing) {
                currPDU = new PDU()
                currPDU.add(new VariableBinding(oid))
                currPDU.setType(PDU.GETNEXT)
                snmp.sendPDU(currPDU, target, null, listenerSing)
                outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,oid,this, -1, index))
                println("sent single " + currPDU.getRequestID.toInt + " for " + oid)
                index += 1
            }
        }
        def sendNextPDU(snmp:Snmp, listener:ResponseListener, nextOid:OID, baseOid:OID, index:Int) : Unit = {
            currPDU = new PDU()
            currPDU.add(new VariableBinding(nextOid))
            currPDU.setMaxRepetitions(5)
            currPDU.setType(PDU.GETBULK)
            snmp.sendPDU(currPDU, target, null, listener)
            outStanding.put(currPDU.getRequestID.toInt, new OutStanding(currPDU.getRequestID.toInt,baseOid,this, index, -1))
            println("sent " + currPDU.getRequestID + " for " + nextOid)
        }
    }
    case class OutStanding(requestId:int, baseOid:OID, pollTarget:PollTarget, colBulkIndex:Int, colSingIndex:Int)

    var targets =Map[String,PollTarget]()
    var outStanding = new ConcurrentHashMap[Int,OutStanding]()

    val transport = new DefaultUdpTransportMapping()
    val snmp = new Snmp(transport)



    class MyTimeoutPolicy extends AnyRef with TimeoutModel {
        var to = 1000L
        override def getRequestTimeout(totalNumberOfRetries:Int, targetTimeout:Long) : long = { to }
        override def getRetryTimeout(retryCount:Int, totalNumberOfRetries:Int, targetTimeout:Long) : Long = {
            if ( retryCount > 0 )
                -1L
            else
                to
        }
    }

    class ReceiverSingle extends AnyRef with ResponseListener {
        override def onResponse(event: ResponseEvent) : Unit = {
            // Always cancel async request when response has been received
            // otherwise a memory leak is created! Not canceling a request
            // immediately can be useful when sending a request to a broadcast
            // address.
            try {
                event.getSource().asInstanceOf[Snmp].cancel(event.getRequest(), this)
/*
                println("Thread " + Thread.currentThread.getName +
                    " response from: " + event.getPeerAddress +
                    " with id: " + (if (event.getResponse == null ) -1 else event.getResponse.getRequestID)  )
*/
            }
            catch { case e:Exception => e.printStackTrace }
            val out = outStanding.remove(event.getRequest.getRequestID.toInt)
            if ( event != null ) {
                val r = event.getResponse()
                if ( r != null && r.size == 1 ) {
                    //println("GOT single: " + r.get(0).getVariable)
                    val ip = event.getPeerAddress.toString
                    val target = targets.get(ip)
                    target.get.dataSing(out.colSingIndex)=r.get(0)
                    target.get.doCompletion()
                }
            }
        }
    }
    class ReceiverBulk extends AnyRef with ResponseListener {

        def isExpectedOid(event: ResponseEvent, oid:OID) : Option[OID] = {
            var partOf = true
            val expectedOid = event.getRequest.get(0).getOid()
            if ( oid.size != expectedOid.size-1 ) None
            if ( !oid.startsWith(expectedOid) ) None

            new Some[OID](oid.nextPeer())
        }

        override def onResponse(event: ResponseEvent) : Unit = {
            // Always cancel async request when response has been received
            // otherwise a memory leak is created! Not canceling a request
            // immediately can be useful when sending a request to a broadcast
            // address.
            try {
                event.getSource().asInstanceOf[Snmp].cancel(event.getRequest(), this)
/*
                println("Thread " + Thread.currentThread.getName +
                    " response from: " + event.getPeerAddress +
                    " with id: " + (if (event.getResponse == null ) -1 else event.getResponse.getRequestID)  )
*/
            }
            catch { case e:Exception => e.printStackTrace }
            val out = outStanding.remove(event.getRequest.getRequestID.toInt)

            // println("Received response PDU is: "+event.getResponse())
            if ( event != null ) {
                val r = event.getResponse()
                if ( r != null && r.size > 0 ) {

                    val ip = event.getPeerAddress.toString
                    val target = targets.get(ip)

                    var jumpedRails=false
                    var lastoid=new OID("")
//                    println("got response size: " + r.size)
                    var i = 0
                    // slight violation of encapsulation but faster this way since we
                    // must walk the result the result for jumping
                    var d = target.get.dataBulk()(out.colBulkIndex)
                    //var d = dd(out.colIndex)
                    for ( i <- 0 to r.size-1) {
                        val vb = r.get(i)
                        val oid = vb.getOid
                        lastoid = oid
                        val v = vb.getVariable()
                        // make sure we have not jumped the rails...

//                        println("this oid: " + oid + " base oid: " + out.baseOid)
                        if ( oid.startsWith(out.baseOid) ) {
                            val lastOidIndex = vb.getOid().last()
                            d(lastOidIndex)=vb
/*                            print("i: " + i + "{ ")
                            v match {
                                case vx:TimeTicks => print(" timeticks \"" + vx.toString() + "\" ")
                                case vx:OctetString => print(" \"" + vx.toASCII('\0') + "\" ")
                                case _ => print (" " + v + " ")
                            }
                            println(" } ")
*/
                        } else {
                            println("JUMPED")
                            jumpedRails=true
                        }

                    }

                    if ( !jumpedRails) {
                        // we are not done yet so get next oid
                        val nextoid = out.baseOid.clone.asInstanceOf[OID]
                        nextoid.append(lastoid.last)
                        if ( target != None ) {
                            target.get.sendNextPDU(snmp, this, nextoid, out.baseOid, out.colBulkIndex)
                            println("#### NEXT")
                        }
                        else {
                            println("missing entry for IP: " + ip)
                        }
                    } else {
                        target.get.doCompletion()
                        println("##### DONE")
                    }
                } else {
                    println("empty response")
                }

            }
        }
    }

    override def run : Unit = {
        
        // setup listener
        snmp.setTimeoutModel(new MyTimeoutPolicy)
        transport.listen()
        val listenerBulk = new ReceiverBulk
        val listenerSing = new ReceiverSingle

        // setup targets

        val pollsetBulk:Array[OID] = Array[OID](new OID("1.3.6.1.2.1.2.2.1.2"),  new OID("1.3.6.1.2.1.2.2.1.10"),  new OID("1.3.6.1.2.1.2.2.1.16"))
        val pollsetSing:Array[OID] = Array[OID](new OID(".1.3.6.1.2.1.1.3"), new OID(".1.3.6.1.2.1.1.1"))

        targets = Map("192.168.0.198/161" -> new PollTarget("192.168.0.198", pollsetBulk, pollsetSing), 
                      "127.0.0.1/161"     -> new PollTarget("127.0.0.1", pollsetBulk, pollsetSing) )

        //val pollset:Array[OID] = Array[OID](new OID("1.3.6.1.2.1.2.2.1.2"))
        //targets = Map("127.0.0.1/161" -> new PollTarget("127.0.0.1", pollset) )

        //val targets = Map(1 -> 1, 1 -> 2 )
        for ( pt <- targets.values ) {
            pt.sendInitialPDU(snmp, listenerBulk, listenerSing)
        }


        println("sleep..")
        Thread.sleep(500000L);
        0
    }

    def spawnThread : Unit = {
        var t:Thread = new Thread() {
            override def run : Unit = { println({"hi"}) }
        }
        t.start()
        Thread.`yield`()
        println("started")
        t.join()
        println("done")
    }
    
}