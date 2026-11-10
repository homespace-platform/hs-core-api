package com.hs.listing.membership;

public enum MembershipTier {
    FREE(3, 0), STARTER(10, 49_000), PRO(30, 129_000), BUSINESS(100, 349_000);

    public final int uses;
    public final int priceVnd;

    MembershipTier(int uses, int priceVnd) {
        this.uses = uses;
        this.priceVnd = priceVnd;
    }
}
