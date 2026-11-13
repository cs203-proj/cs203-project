package com.cs203.healthwatch.consent;

/**
 * The consent notice shown at sign-up (CG-8).
 *
 * <p>Both the wording and the version live here so there is exactly one source of truth: GET /consent serves this
 * text, and POST /auth/register will only accept a registration that quotes this version back. If the wording
 * changes in a way that changes what the user is agreeing to, bump {@link #VERSION} — the stored version on each
 * user then tells us who agreed to which wording.
 */
public final class ConsentTerms {

    /** Bump this whenever {@link #TEXT} changes in substance, not for typo fixes. */
    public static final String VERSION = "1.0";

    /**
     * What the user is agreeing to. Three things the ticket requires it to say: health data is used only to
     * personalise warnings, it is never used for diagnosis, and the user can delete their profile (CG-7).
     */
    public static final String TEXT = """
            HealthWatch uses the information in your profile — your region, your audience group, and any health \
            conditions you choose to tell us about — for one purpose only: to decide which public health warnings \
            are relevant to you, and to word them for your situation.

            This is not a medical service. Your health information is never used to diagnose you, to give you \
            medical advice, or to make any decision about your care. If you are unwell, speak to a doctor.

            Your information is not sold, and it is not shared with advertisers.

            You stay in control. You can change your profile at any time, and you can delete it. Deleting your \
            profile removes your health information from HealthWatch.""";

    private ConsentTerms() {
    }
}
