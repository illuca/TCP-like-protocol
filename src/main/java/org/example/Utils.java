package org.example;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
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

    public static int getLength(byte[] array) {
        return array == null ? 0 : Array.getLength(array);
    }

    public static int seq(int sequenceNumber) {
        return sequenceNumber % 65536;
    }

    public static boolean dropPacket(float flp, int type) {
        if (type == STPSegment.RESET) {
            return false;
        }
        Random random = new Random();
        float randomValue = random.nextFloat();
        return !(randomValue >= flp);
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

    public static String durationString(long currentTime, long startTime) {
        long duration = currentTime - startTime;
        double time = (double) duration / 1000000;
        String format = df.format(time);
        return format;
    }

    public static boolean isTimeout(STPSegment stp, long startTime) {
        double duration = Utils.duration(System.nanoTime(), startTime);
        if (duration > stp.getAckTime()) {
            return true;
        }
        return false;
    }

    public static void writeLogToFile(StringBuffer log, String filename) {

        File file = new File(filename);
        try (FileWriter fileWriter = new FileWriter(file);
             BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)) {
            bufferedWriter.write(log.toString());
        } catch (IOException e) {
        }
    }
}
