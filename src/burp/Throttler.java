package burp;

import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


class DistributeDamage {
    ReadWriteLock lock = new ReentrantReadWriteLock();
    Lock writeLock = lock.writeLock();
    Lock readLock = lock.readLock();

    String host;
    int delay;
    int concurrency;
    int numRequestsInFlight;
    Long time;
    DistributeDamage(String host, int delay, int concurrency){
        this.host = host;
        this.delay = delay;
        this.concurrency = concurrency;
        this.numRequestsInFlight = 1;

        updateTime();
    }

    void updateTime(){
        try {
            writeLock.lock();
            this.time = new Date().getTime();
        } finally {
            writeLock.unlock();
        }
    }

    int getDelay(){
        try {
            readLock.lock();
            return this.delay;
        } finally {
            readLock.unlock();
        }
    }

    Long getTime(){
        try {
            readLock.lock();
            return this.time;
        } finally {
            readLock.unlock();
        }
    }

    Long getLastUpdate(){
        try {
            readLock.lock();
            return this.time;
        } finally {
            readLock.unlock();
        }
    }

    int getConcurrency(){
        try {
            readLock.lock();
            return this.concurrency;
        } finally {
            readLock.unlock();
        }
    }


    void setDelay(int delay){
        try {
            writeLock.lock();
            this.delay = delay;
        } finally {
            writeLock.unlock();
        }
    }

    void setConcurrency(int concurrency){
        try {
            writeLock.lock();
            this.concurrency = concurrency;
        } finally {
            writeLock.unlock();
        }
    }

    int getRequestsInFlight(){
        try {
            readLock.lock();
            return this.numRequestsInFlight;
        } finally {
            readLock.unlock();
        }
    }    

    void addRequestInFlight(){
        try {
            writeLock.lock();
            this.numRequestsInFlight +=1;
        } finally {
            writeLock.unlock();
        }
    }

    void decRequestInFlight(){
        try {
            writeLock.lock();
            this.numRequestsInFlight -=1;
        } finally {
            writeLock.unlock();
        }
    }
}



class Throttler implements IHttpListener {
    private HashMap<String, DistributeDamage> locks = new HashMap<>();
    String instanceCacheBust;

    DistributeDamage master;

    Throttler() {
        instanceCacheBust = Utilities.generateCanary();
        master = new DistributeDamage("master", Utilities.globalSettings.getInt("delay"), Utilities.globalSettings.getInt("concurrency"));
    }

    public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
        Lock spiderLock = null;
        if (toolFlag == IBurpExtenderCallbacks.TOOL_SPIDER) {
            spiderLock = Utilities.spiderLock.readLock();
            spiderLock.lock();
        }
        try {
            // If its a request.
            if (messageIsRequest /*&& Utilities.THROTTLED_COMPONENTS.contains(toolFlag)*/) {
                //delayRequestMaster(hostname);
                String hostname = messageInfo.getHttpService().getHost();
                delayRequest(hostname);
            }
            // If its a response.
            if (!messageIsRequest /*&& Utilities.THROTTLED_COMPONENTS.contains(toolFlag)*/) {
                String hostname = messageInfo.getHttpService().getHost();
                response(hostname);
            }
        }
        finally {
            if (spiderLock != null) {
                spiderLock.unlock();
            }
        }
    }

    private void suspendRequest() {
        while (Utilities.globalSettings.getBoolean("pause all traffic")) {
            try {
                Thread.sleep(Utilities.globalSettings.getInt("delay"));
            } catch (java.lang.InterruptedException e) {
                Utilities.err("Interrupted while sleeping, aborting suspension");
                return;
            }
        }
    }


    public void delayRequestMaster(String hostname){
        Utilities.err("Its a master request");
        if (hostname.equals("bwapps") || hostname.equals("labs-linux")) {
            return;
        }

        suspendRequest();

    
        // master.setConcurrency(Utilities.globalSettings.getInt("concurrency"));
        // master.setDelay(Utilities.globalSettings.getInt("delay"));


        long waitFor = Utilities.globalSettings.getInt("delay") - (new Date().getTime() - master.getLastUpdate());
        if (waitFor > 0) {
            try {
                Thread.sleep(waitFor);
            } catch (java.lang.InterruptedException e) {
                Utilities.err("Interrupted while sleeping");
            }
        }
        master.updateTime();


        while(master.getRequestsInFlight() >= Utilities.globalSettings.getInt("concurrency")){
            try {
                Thread.sleep(master.getDelay());
            } catch (java.lang.InterruptedException e) {
                Utilities.err("Interrupted while waiting for concurrency limit to decrease");
            }
        }

        master.addRequestInFlight();

    }

    public void delayRequest(String hostname){
        if (hostname.equals("bwapps") || hostname.equals("labs-linux")) {
            return;
        }

        suspendRequest();

        //synchronized(hostname.intern()) {

            if (locks.containsKey(hostname)) {
                DistributeDamage damage = locks.get(hostname);

                // currently just override with the global master settings for each host
                damage.setConcurrency(Utilities.globalSettings.getInt("concurrency"));
                damage.setDelay(Utilities.globalSettings.getInt("delay"));

                long waitFor = damage.getDelay() - (new Date().getTime() - damage.getLastUpdate());
                if (waitFor > 0) {
                    try {
                        Thread.sleep(waitFor);
                    } catch (java.lang.InterruptedException e) {
                        Utilities.err("Interrupted while sleeping");
                    }
                }
                damage.updateTime();


                while(damage.getRequestsInFlight() >= damage.getConcurrency()){
                    try {
                        Thread.sleep(damage.getDelay());
                    } catch (java.lang.InterruptedException e) {
                        Utilities.err("Interrupted while waiting for concurrency limit to decrease");
                    }
                }

                damage.addRequestInFlight();

            }else{
                // insert new distribute damage object with default settings
                locks.put(hostname, new DistributeDamage(hostname,Utilities.globalSettings.getInt("delay"),Utilities.globalSettings.getInt("concurrency")));
            }

        //}
    }


    public void response(String hostname){
        if (hostname.equals("bwapps") || hostname.equals("labs-linux")) {
            return;
        }

        //master.decRequestInFlight();
        //synchronized(hostname.intern()) {
            if (locks.containsKey(hostname)) {
                locks.get(hostname).decRequestInFlight();
            }
        //}
    }

}
