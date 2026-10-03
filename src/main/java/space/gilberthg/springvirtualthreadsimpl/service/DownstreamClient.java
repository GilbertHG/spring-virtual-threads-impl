package space.gilberthg.springvirtualthreadsimpl.service;

import org.springframework.stereotype.Component;

@Component
public class DownstreamClient {

    public static final long BLOCK_MILLIS = 300;

    public String inventory() {
        block("inventory");
        return "42 units in stock";
    }

    public String pricing() {
        block("pricing");
        return "USD 19.00";
    }

    public String shipping() {
        block("shipping");
        return "2 days";
    }

    private static void block(String source) {
        try {
            Thread.sleep(BLOCK_MILLIS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling " + source, ex);
        }
    }

}
