package org.tinyradius.core.packet.response;

import org.tinyradius.core.attribute.type.RadiusAttribute;
import org.tinyradius.core.dictionary.Dictionary;
import org.tinyradius.core.packet.BaseRadiusPacket;
import org.tinyradius.core.RadiusPacketException;

import java.util.List;

public class GenericResponse extends BaseRadiusPacket<RadiusResponse> implements RadiusResponse {

    /**
     * Builds a Radius packet with the given type, identifier and attributes.
     * <p>
     * Use {@link RadiusResponse#create(Dictionary, byte, byte, byte[], List)}
     * where possible as that automatically creates Access/Accounting
     * variants as required.
     *
     * @param dictionary    custom dictionary to use
     * @param type          packet type
     * @param identifier    packet identifier
     * @param authenticator can be null if creating manually
     * @param attributes    list of RadiusAttribute objects
     */
    public GenericResponse(Dictionary dictionary, byte type, byte identifier, byte[] authenticator, List<RadiusAttribute> attributes) {
        super(dictionary, type, identifier, authenticator, attributes);
    }

    @Override
    public RadiusResponse encodeResponse(String sharedSecret, byte[] requestAuth) throws RadiusPacketException {
        final RadiusResponse response = withAttributes(encodeAttributes(requestAuth, sharedSecret));

        final byte[] auth = response.genHashedAuth(sharedSecret, requestAuth);
        return new GenericResponse(getDictionary(), getType(), getId(), auth, response.getAttributes());
    }

    @Override
    public RadiusResponse decodeResponse(String sharedSecret, byte[] requestAuth) throws RadiusPacketException {
        verifyPacketAuth(sharedSecret, requestAuth);
        return withAttributes(decodeAttributes(requestAuth, sharedSecret));
    }

    @Override
    public GenericResponse withAttributes(List<RadiusAttribute> attributes) {
        return new GenericResponse(getDictionary(), getType(), getId(), getAuthenticator(), attributes);
    }
}
