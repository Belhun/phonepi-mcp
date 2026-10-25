package dev.phonepi.companion.health

const val HEALTH_PRIVACY_TITLE = "Sleep and health data"

const val HEALTH_PRIVACY_BODY =
    "PhonePi reads sleep, steps, heart rate, resting heart rate, exercise, and blood oxygen " +
        "from Health Connect on this phone. Galaxy Watch 7 data reaches Health Connect when " +
        "Samsung Health is allowed to write those types.\n\n" +
        "Bots on your box see daily totals only: last night's sleep (start, end, total, stages), " +
        "steps today and the last 7 days, resting and average heart rate, and recent workouts. " +
        "PhonePi does not send raw heart-rate streams.\n\n" +
        "A copy lives on this phone and on your box under ~/.phonepi/health (directory mode 700, " +
        "files 600). Nothing is sent to a third-party cloud. You can pause sync or delete the " +
        "cache in PhonePi, and you can revoke Health Connect permissions in system settings.\n\n" +
        "Samsung Health can lag minutes to hours before Health Connect has the latest night. " +
        "PhonePi shows the time it last read Health Connect, not the time the watch recorded a beat."

const val HEALTH_PRIVACY_CONTINUE = "I understand, continue"
const val HEALTH_PRIVACY_CLOSE = "Close"
