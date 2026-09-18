package org.leo.jmg;

import org.leo.core.entity.Disguise;

public final class TrafficTestFixtures {

    private TrafficTestFixtures() {
    }

    public static Disguise requestDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data){return data;}");
        return disguise;
    }

    public static Disguise responseDisguise() {
        Disguise disguise = new Disguise();
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] data){return data;}");
        return disguise;
    }
}
