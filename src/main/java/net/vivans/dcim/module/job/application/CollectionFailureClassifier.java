package net.vivans.dcim.module.job.application;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.TimeoutException;
public final class CollectionFailureClassifier {
    private CollectionFailureClassifier() {}
    public static String classify(Throwable failure) {
        boolean deviceError=false;
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SocketTimeoutException || cause instanceof TimeoutException)return "TIMEOUT";
            if(cause instanceof ConnectException || cause instanceof NoRouteToHostException || cause instanceof UnknownHostException)return "UNREACHABLE";
            deviceError|=cause instanceof IOException || cause instanceof IllegalArgumentException;
        }
        return deviceError?"DEVICE_ERROR":"ERROR";
    }
}
