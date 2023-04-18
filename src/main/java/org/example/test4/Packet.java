package org.example.test4;
import java.io.Serializable;

public class Packet implements Serializable {
    public enum Type {
        DATA,
        ACK
    }

    private Type type;
    private int sequenceNumber;
    private byte[] data;

    public Packet(Type type, int sequenceNumber, byte[] data) {
        this.type = type;
        this.sequenceNumber = sequenceNumber;
        this.data = data;
    }

    public Type getType() {
        return type;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getData() {
        return data;
    }
}
