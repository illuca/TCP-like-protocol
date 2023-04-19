package org.example;

import java.util.Random;

public class Utils {
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

    public static boolean shouldProcessPacket(float flp) {
        Random random = new Random();
        float randomValue = random.nextFloat();
        return randomValue >= flp;
    }
    public static boolean shouldSendPacket(float rlp) {
        Random random = new Random();
        double randomValue = random.nextFloat();
        return randomValue >= rlp;
    }

}
