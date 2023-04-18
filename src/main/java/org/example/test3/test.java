package org.example.test3;

public class test {
    public static byte[] intToBytes(int value) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (value >> 24); // Most significant byte (MSB)
        bytes[1] = (byte) (value >> 16);
        bytes[2] = (byte) (value >> 8);
        bytes[3] = (byte) value; // Least significant byte (LSB)
        return bytes;
    }

    public static void main(String[] args) {
        int intValue = 5;
        byte[] byteArray = intToBytes(intValue);

// Print the byte array in hexadecimal format
        for (byte b : byteArray) {
            System.out.printf("%02X ", b);
        }

    }
}
