package io.github.markpollack.journal.tck.venn;

/** What became of one native value when compared with the journal's record. */
public enum Landing {
    /** Found as a typed field of the journal's record. */
    LANDED,
    /** Found only inside a {@code vendor_event}, which keeps the vendor's record whole. */
    KEPT_WHOLE,
    /** A short string, a boolean or a small integer: too common to prove anything by value. Needs a rule. */
    LOW_INFORMATION,
    /** Not found anywhere in the journal's record. */
    NEITHER
}
