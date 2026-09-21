package com.cursorforandroid.screenshots

/**
 * The version the Settings frames show (`07`, `20`, `21`, `65`–`67`, `91`). Fixed, and not the build's own: the frames
 * are compared pixel for pixel with `screenshots/`, and the build's version changes with every release, which used
 * to re-record seven of them per cut. It is the version they showed when it was pinned; it never has to move.
 */
internal const val SCREENSHOT_APP_VERSION = "0.3.50"
