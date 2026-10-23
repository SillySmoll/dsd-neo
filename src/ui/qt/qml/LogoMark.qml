// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 by arancormonk <180709949+arancormonk@users.noreply.github.com>

import QtQuick

// The app mark (WTDW Tracker): a map pin with radio waves, in the theme's blue
// with a gold centre, inside a panel square. Drawn, not an image, so both
// themes get it for free.
UiPanel {
    implicitWidth: 62
    implicitHeight: 62

    Canvas {
        id: mark
        anchors.centerIn: parent
        width: 44
        height: 44
        onPaint: {
            const ctx = getContext("2d");
            ctx.reset();
            const cx = width / 2, cy = height * 0.44, r = width * 0.17, tip = height * 0.95;
            // Waves either side of the pin head.
            ctx.strokeStyle = Theme.textPrimary;
            ctx.lineCap = "round";
            ctx.lineWidth = width * 0.065;
            [1.55, 2.15].forEach(function (k) {
                const R = r * k;
                ctx.beginPath(); ctx.arc(cx, cy, R, -62 * Math.PI / 180, -18 * Math.PI / 180, false); ctx.stroke();
                ctx.beginPath(); ctx.arc(cx, cy, R, 198 * Math.PI / 180, 242 * Math.PI / 180, false); ctx.stroke();
            });
            // Pin: head circle joined to the tip by its two tangents.
            const a = Math.asin(r / (tip - cy));
            const grad = ctx.createLinearGradient(0, cy - r, 0, tip);
            grad.addColorStop(0, Theme.cyan);
            grad.addColorStop(1, Qt.darker(Theme.cyan, 1.5));
            ctx.fillStyle = grad;
            ctx.beginPath();
            ctx.moveTo(cx, tip);
            ctx.arc(cx, cy, r, Math.PI / 2 + a, Math.PI / 2 - a, false);
            ctx.closePath();
            ctx.fill();
            ctx.fillStyle = Theme.magenta;
            ctx.beginPath(); ctx.arc(cx, cy, r * 0.42, 0, 2 * Math.PI); ctx.fill();
        }
        Connections {
            target: Theme
            function onDarkChanged() { mark.requestPaint(); }
        }
    }
}
