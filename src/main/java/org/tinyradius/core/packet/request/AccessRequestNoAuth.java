package org.tinyradius.core.packet.request;

import org.tinyradius.core.attribute.type.RadiusAttribute;
import org.tinyradius.core.dictionary.Dictionary;
import org.tinyradius.core.RadiusPacketException;

import java.util.List;

public class AccessRequestNoAuth extends AccessRequest<AccessRequestNoAuth> {

    public AccessRequestNoAuth(Dictionary dictionary, byte identifier, byte[] authenticator, List<RadiusAttribute> attributes) {
        super(dictionary, identifier, authenticator, attributes);
    }

    @Override
    protected AccessRequestFactory<AccessRequestNoAuth> factory() {
        return AccessRequestNoAuth::new;
    }

    @Override
    public RadiusRequest encodeRequest(String sharedSecret) throws RadiusPacketException {
        final RadiusRequest radiusRequest = super.encodeRequest(sharedSecret);
        checkMessageAuth(radiusRequest.getAttributes()); // MessageAuthSupport should append Message-Auth during encoding
        return radiusRequest;
    }

    @Override
    public RadiusRequest decodeRequest(String sharedSecret) throws RadiusPacketException {
        checkMessageAuth(getAttributes());
        return super.decodeRequest(sharedSecret);
    }

    private static void checkMessageAuth(List<RadiusAttribute> attributes) {
        final long count = attributes.stream()
                .filter(a -> a.getType() == MESSAGE_AUTHENTICATOR)
                .count();
        if (count != 1)
            logger.warn("AccessRequest without one of User-Password/CHAP-Password/ARAP-Password/EAP-Message " +
                    "should contain a Message-Authenticator");
    }
}
