package org.tinyradius.core.attribute.type;

import org.tinyradius.core.dictionary.Dictionary;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This class represents a Radius attribute which only contains a string.
 */
public class StringAttribute extends OctetsAttribute {

    public StringAttribute(Dictionary dictionary, int vendorId, int type, byte[] data) {
        super(dictionary, vendorId, type, data);
        if (data.length == 0)
            throw new IllegalArgumentException("String attribute value should be min 1 octet, actual: " + data.length);
    }

    public StringAttribute(Dictionary dictionary, int vendorId, int type, String value) {
        this(dictionary, vendorId, type, value.getBytes(UTF_8));
    }

    @Override
    public String getValueString() {
        return new String(getValue(), UTF_8);
    }
}
