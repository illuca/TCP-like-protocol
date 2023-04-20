package org.example;

import java.text.DecimalFormat;
import java.util.Random;

public class Utils {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    public static String getStringType(int type) {
        switch (type) {
            case STPSegment.DATA:
                return "DATA";
            case STPSegment.ACK:
                return "ACK";
            case STPSegment.SYN:
                return "SYN";
            case STPSegment.FIN:
                return "FIN";
            default:
                return "RESET";
        }
    }

    public static int seq(int sequenceNumber) {
        return sequenceNumber % 65536;
    }

    public static boolean shouldProcessPacket(float flp, int type) {
        if (type == STPSegment.RESET) {
            return true;
        }
        Random random = new Random();
        float randomValue = random.nextFloat();
        return randomValue >= flp ;
    }
    public static boolean shouldSendPacket(float rlp) {
        Random random = new Random();
        double randomValue = random.nextFloat();
        return randomValue >= rlp;
    }

    public static double duration(long currentTime, long startTime) {
        long duration = currentTime - startTime;
        double time = (double) duration / 1000000;
        String format = df.format(time);
        return Double.parseDouble(format);
    }
}
