package org.leo.core.net.layer;

import org.leo.core.entity.Disguise;

public class ResponseLayer {
    private Disguise disguise;

    public ResponseLayer(Disguise disguise) {
        this.disguise = disguise;
    }

    public Disguise getDisguise() {
        return disguise;
    }

    public void setDisguise(Disguise disguise) {
        this.disguise = disguise;
    }
}
