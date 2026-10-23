// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 by arancormonk <180709949+arancormonk@users.noreply.github.com>

import QtQuick

// The app mark (WTDW Tracker): the lattice tower and radio waves from the WTDW
// logo, inside a panel square. Drawn, not an image, so both
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
            const cx = width / 2, top = height * 0.30, base = height * 0.97, ball = width * 0.075;
            // Waves either side of the tower top, cyan fading to blue like the WTDW logo.
            ctx.lineCap = "round";
            ctx.lineWidth = width * 0.055;
            const grad = ctx.createLinearGradient(0, 0, 0, height * 0.6);
            grad.addColorStop(0, Theme.cyan);
            grad.addColorStop(1, Theme.magenta);
            ctx.strokeStyle = grad;
            [0.16, 0.26, 0.36].forEach(function (k) {
                const R = width * k;
                ctx.beginPath(); ctx.arc(cx, top, R, -50 * Math.PI / 180, 42 * Math.PI / 180, false); ctx.stroke();
                ctx.beginPath(); ctx.arc(cx, top, R, 138 * Math.PI / 180, 230 * Math.PI / 180, false); ctx.stroke();
            });
            // Lattice tower: two legs, cross braces, ball on top.
            const half = width * 0.21, apexY = top + ball * 0.6;
            function leg(t, side) { return [cx + side * half * t, apexY + (base - apexY) * t]; }
            ctx.strokeStyle = Theme.textPrimary;
            ctx.lineWidth = width * 0.045;
            ctx.beginPath(); ctx.moveTo(cx, apexY); ctx.lineTo(cx - half, base); ctx.moveTo(cx, apexY); ctx.lineTo(cx + half, base); ctx.stroke();
            ctx.lineWidth = width * 0.035;
            const ts = [0.12, 0.34, 0.62, 1.0];
            ctx.beginPath();
            for (let i = 0; i < ts.length - 1; ++i) {
                const a = leg(ts[i], -1), b = leg(ts[i], 1), c = leg(ts[i + 1], -1), d = leg(ts[i + 1], 1);
                ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]);
                ctx.moveTo(a[0], a[1]); ctx.lineTo(d[0], d[1]);
                ctx.moveTo(b[0], b[1]); ctx.lineTo(c[0], c[1]);
            }
            ctx.stroke();
            ctx.fillStyle = Theme.textPrimary;
            ctx.beginPath(); ctx.arc(cx, top, ball, 0, 2 * Math.PI); ctx.fill();
        }
        Connections {
            target: Theme
            function onDarkChanged() { mark.requestPaint(); }
        }
    }
}
