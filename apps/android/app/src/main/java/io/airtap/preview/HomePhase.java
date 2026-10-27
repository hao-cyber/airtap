package io.airtap.preview;

/** Presentation only. Busy requests never choose a page or change authorization. */
enum HomePhase {
    SETUP, READY, WAITING, CONFIRM, APPROVED, ACTIVE, ENDED;

    static HomePhase resolve(boolean enrolled, boolean permissionsReady, boolean invitation,
                             boolean candidate, boolean grantAvailable, boolean authorized,
                             boolean ended) {
        if (authorized) return ACTIVE;
        if (grantAvailable) return APPROVED;
        if (candidate) return CONFIRM;
        if (invitation) return WAITING;
        if (ended) return ENDED;
        return enrolled && permissionsReady ? READY : SETUP;
    }
}
