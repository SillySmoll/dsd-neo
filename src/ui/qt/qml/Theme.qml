// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 by arancormonk <180709949+arancormonk@users.noreply.github.com>

pragma Singleton
import QtQuick

// The "Instrument" token set: the launcher icon's cyan→magenta on near-black, with
// an exact light counterpart whose accents are the same hues darkened for contrast
// on white. Every color and size in the UI resolves through here — screens never
// hard-code a hex value, so the appearance switch is one property flip.
QtObject {
    // 0 = follow the OS, 1 = light, 2 = dark; bound to the persisted preference.
    readonly property int appearance: (typeof prefs !== "undefined" && prefs) ? prefs.appearance : 0
    // Qt.ColorScheme: 0 unknown, 1 light, 2 dark. Unknown lands on dark — the brand
    // surface — rather than on a guess at the OS's intent.
    readonly property bool dark: appearance === 2 || (appearance === 0 && Application.styleHints.colorScheme !== 1)

    readonly property string sans: (typeof sansFontFamily !== "undefined") ? sansFontFamily : "sans-serif"
    readonly property string mono: (typeof monoFontFamily !== "undefined") ? monoFontFamily : "monospace"

    // The fixed pixel type sizes otherwise ignore the platform's larger text
    // preference. Kept writable so compact-height tests can exercise that layout.
    property real fontScale: systemFontScale()
    function systemFontScale() {
        var revision = fontRevision;
        if (typeof decoderHost !== "undefined" && decoderHost && decoderHost.usesPlatformFontScaling === true)
            return decoderHost.fontPixelSize(16) / 16;
        return Math.min(1.6, Math.max(1, Qt.application.font.pixelSize / 16));
    }
    function keyboardTop(item) {
        var host = typeof decoderHost !== "undefined" ? decoderHost : null;
        if (host && typeof host.keyboardTop === "number" && host.keyboardTop >= 0)
            return Math.min(item.height, item.mapFromItem(null, 0, host.keyboardTop).y);
        var keyboard = Qt.inputMethod.keyboardRectangle;
        return Qt.inputMethod.visible && keyboard.height > 0 ? Math.min(item.height, item.mapFromItem(null, 0, keyboard.y).y) : item.height;
    }
    readonly property int fontRevision: (typeof decoderHost !== "undefined" && decoderHost && decoderHost.fontRevision !== undefined) ? decoderHost.fontRevision : 0
    function fontSize(pixels) {
        var revision = fontRevision;
        if (typeof decoderHost !== "undefined" && decoderHost && decoderHost.usesPlatformFontScaling === true)
            return decoderHost.fontPixelSize(pixels);
        return pixels * fontScale;
    }
    function revealFocus(scroll, content, focus) {
        if (!scroll || !content || !focus)
            return;
        var ancestor = focus;
        while (ancestor && ancestor !== content)
            ancestor = ancestor.parent;
        if (ancestor !== content)
            return;
        var target = focus.parent && focus.parent.input === focus ? focus.parent : focus;
        if (target.height > scroll.height)
            target = focus;
        var point = target.mapToItem(content, 0, 0);
        var next = scroll.contentY;
        if (point.y < next)
            next = point.y;
        else if (point.y + target.height > next + scroll.height)
            next = point.y + target.height - scroll.height;
        // A ListView passes its contentItem (issue #718): its content does not
        // start at 0. A header sits at negative y and a prepend moves the start
        // further up, so the bounds are the view's own origin, not 0.
        var start = content === scroll.contentItem ? scroll.originY : 0;
        scroll.contentY = Math.max(start, Math.min(next, start + scroll.contentHeight - scroll.height));
    }
    function resetFontScale() {
        fontScale = Qt.binding(function () {
            return systemFontScale();
        });
    }

    // Surfaces
    readonly property color bg: dark ? "#03060B" : "#F1F5FA"
    readonly property color panel: dark ? "#0A111C" : "#FFFFFF"
    readonly property color panelBorder: dark ? "#163763" : "#D2DEEE"
    readonly property color divider: dark ? "#0D1A2C" : "#E7EEF7"
    readonly property color controlBorder: dark ? "#1A3C6B" : "#C8D6EA"

    // Text
    readonly property color textPrimary: dark ? "#F2F6FA" : "#050B14"
    readonly property color textSecondary: dark ? "#A9B8CC" : "#4A5C74"
    readonly property color textSubdued: dark ? "#A9B8CC" : "#4A5C74"
    readonly property color buttonSecondaryText: dark ? "#D3DCE8" : "#2A3D58"

    // Accents (WTDW logo): "cyan" is the electric cyan, "magenta" the deeper wave blue; darkened in light mode
    // so they hold contrast on white. The names stay so every screen and test keeps resolving them.
    readonly property color cyan: dark ? "#1FC8FF" : "#0070C9"
    readonly property color magenta: dark ? "#2E7BFF" : "#0B48C2"

    readonly property color alert: dark ? "#FFAC88" : "#9C3600"
    readonly property color alertBorder: Qt.alpha(alert, dark ? 0.55 : 0.45)

    // Derived fills
    readonly property color toggleOnTrack: Qt.alpha(cyan, dark ? 0.25 : 0.22)
    readonly property color toggleOffTrack: dark ? "#1A3C6B" : "#C8D6EA"
    readonly property color toggleKnobOff: dark ? "#7D8DA3" : "#FFFFFF"
    readonly property color chipSelectedFill: Qt.alpha(cyan, dark ? 0.10 : 0.08)
    readonly property color encBorder: Qt.alpha(magenta, dark ? 0.40 : 0.35)

    // Radii
    readonly property int radiusPanel: 12
    readonly property int radiusButton: 8
    readonly property int radiusChip: 8

    // Spacing
    readonly property int screenPadding: 18
    readonly property int cardPadding: 16
    readonly property int gap: 13
    readonly property int minimumTouchSize: 48
    readonly property int formWidth: 640

    // One call row. Also the slack the call lists allow around the top before
    // they stop treating the reader as parked on the latest call.
    readonly property int rowHeight: 58
}
