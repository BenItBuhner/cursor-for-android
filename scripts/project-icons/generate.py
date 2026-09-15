#!/usr/bin/env python3
"""Generates app/src/main/java/com/cursorforandroid/ui/icons/ProjectIconCatalog.kt.

Cursor Projects carry an `appearance {icon, color_id}`; the icon is one of the names of the desktop's own icon font
("Cursor Icons 16", copyright Anysphere, not redistributable), and the picker in the Agents Window offers every
glyph of that font. cursor-icon-names.json holds those names as extracted from the desktop bundle (see its `source`);
this script pairs each of them with an open-licensed glyph of the same meaning — Lucide (ISC) for line icons,
Simple Icons (CC0 1.0) for brand marks — and writes them out as path data the app draws at runtime.

    python3 scripts/project-icons/generate.py <lucide-static package dir> <simple-icons package dir>

Both directories are the unpacked npm packages (`npm pack lucide-static simple-icons`); the versions used are
recorded in the generated file. Re-run after editing MAPPING or GROUPS below, or after bumping either library.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
OUT = REPO / "app/src/main/java/com/cursorforandroid/ui/icons/ProjectIconCatalog.kt"

L = "l:"  # Lucide, ISC
S = "s:"  # Simple Icons, CC0 1.0
C = "c:"  # the app's own glyphs (CursorIcons)

# Cursor icon id -> glyph. "l:<lucide>" / "s:<simple-icons slug>" / "c:cube". A trailing "!" marks a neutral
# stand-in for a mark no open library carries (the owner asked Simple Icons to drop it, or it was never there);
# "#fill" draws a Lucide outline filled; "@<deg>" rotates it about the centre.
MAPPING: dict[str, str] = {
    # --- Agents, account, alerts
    "account": L + "circle-user", "add": L + "plus", "agent": L + "navigation", "agent-circle": L + "circle-arrow-out-up-right",
    "agent-square": L + "square-arrow-out-up-right", "agents": L + "send", "agents-swarm": L + "waypoints",
    "alarm-clock": L + "alarm-clock", "alert": L + "triangle-alert", "archive": L + "archive", "army-base": L + "tent",
    # --- Arrows
    "arrow-block-down": L + "arrow-big-down", "arrow-block-left": L + "arrow-big-left", "arrow-block-right": L + "arrow-big-right",
    "arrow-block-up": L + "arrow-big-up", "arrow-block-line-down": L + "arrow-big-down-dash", "arrow-block-line-left": L + "arrow-big-left-dash",
    "arrow-block-line-right": L + "arrow-big-right-dash", "arrow-block-line-up": L + "arrow-big-up-dash",
    "arrow-bracket-from-down": L + "arrow-down-from-line", "arrow-bracket-from-left": L + "arrow-left-from-line",
    "arrow-bracket-from-right": L + "arrow-right-from-line", "arrow-bracket-from-up": L + "arrow-up-from-line",
    "arrow-bracket-from-up-dashed": L + "arrow-up-from-dot", "arrow-bracket-to-down": L + "arrow-down-to-line",
    "arrow-bracket-to-left": L + "arrow-left-to-line", "arrow-bracket-to-right": L + "arrow-right-to-line", "arrow-bracket-to-up": L + "arrow-up-to-line",
    "arrow-ccw": L + "rotate-ccw",
    "arrow-circle-down": L + "circle-arrow-down", "arrow-circle-left": L + "circle-arrow-left", "arrow-circle-right": L + "circle-arrow-right", "arrow-circle-up": L + "circle-arrow-up",
    "arrow-down": L + "arrow-down", "arrow-left": L + "arrow-left", "arrow-right": L + "arrow-right", "arrow-up": L + "arrow-up",
    "arrow-left-down": L + "arrow-down-left", "arrow-left-up": L + "arrow-up-left", "arrow-right-down": L + "arrow-down-right", "arrow-right-up": L + "arrow-up-right",
    "arrow-square-down": L + "square-arrow-down", "arrow-square-left": L + "square-arrow-left", "arrow-square-right": L + "square-arrow-right", "arrow-square-up": L + "square-arrow-up",
    "arrow-square-from-down": L + "square-arrow-out-down-right", "arrow-square-from-left": L + "square-arrow-out-down-left",
    "arrow-square-from-right": L + "square-arrow-out-up-right", "arrow-square-from-up": L + "square-arrow-out-up-left",
    "arrow-square-left-down": L + "square-arrow-down-left", "arrow-square-left-up": L + "square-arrow-up-left",
    "arrow-square-right-down": L + "square-arrow-down-right", "arrow-square-right-up": L + "square-arrow-up-right",
    "arrow-square-to-down": L + "square-arrow-down", "arrow-square-to-left": L + "square-arrow-left", "arrow-square-to-right": L + "square-arrow-right", "arrow-square-to-up": L + "square-arrow-up",
    "arrow-swap": L + "arrow-left-right", "arrow-u-up-left": L + "undo-2", "arrow-u-up-right": L + "redo-2",
    "arrows-both-horizontal": L + "move-horizontal", "arrows-both-vertical": L + "move-vertical", "arrows-ccw": L + "refresh-ccw",
    "arrows-ccw-angular": L + "repeat", "arrows-contract": L + "minimize-2", "arrows-contract-simple": L + "shrink", "arrows-down-up": L + "arrow-down-up",
    "arrows-expand": L + "maximize-2", "arrows-expand-simple": L + "expand", "arrows-out-cardinal": L + "move",
    # --- Symbols and objects A-C
    "asterisk": L + "asterisk", "at": L + "at-sign", "atom": L + "atom", "bandaid": L + "bandage", "banknote": L + "banknote", "banknotes-stack": L + "wallet-cards",
    "barbell": L + "dumbbell", "basketball": L + "volleyball", "beach-umbrella": L + "umbrella", "beaker": L + "flask-conical", "beaker-stop": L + "flask-conical-off",
    "beehouse": L + "birdhouse", "bell": L + "bell", "bell-dot": L + "bell-dot", "bell-slash": L + "bell-off", "binary": L + "binary", "binoculars": L + "binoculars",
    "bluetooth": L + "bluetooth", "board-kanban": L + "kanban", "book": L + "book", "book-open": L + "book-open", "bookmark": L + "bookmark", "bowtie": L + "ribbon",
    "bracket-dot": L + "braces", "bracket-error": L + "braces", "brackets-curly": L + "braces",
    "brain": L + "brain", "brain-hourglass": L + "brain-cog", "brain-simple": L + "brain", "brain-simplest": L + "brain", "brain-slash": L + "brain",
    "briefcase": L + "briefcase", "browser": L + "app-window", "browsers": L + "app-window-mac", "brush": L + "brush", "bug": L + "bug", "bugbot": L + "bug-off",
    "building": L + "building", "buildings": L + "building-2", "calculator": L + "calculator", "calendar": L + "calendar", "calendar-hourglass": L + "calendar-clock",
    "camera": L + "camera", "car": L + "car", "cardholder": L + "wallet-cards", "castle": L + "castle", "cd": L + "disc",
    "chart-bars": L + "chart-column", "chart-pie": L + "chart-pie", "chart-pyramid": L + "pyramid", "chart-scatter": L + "chart-scatter",
    "chat-bubble-chevrons-left-right": L + "message-square-code", "chat-bubble-pencil": L + "message-square-diff", "chat-bubbles": L + "messages-square",
    "chat-bubbles-grid": L + "messages-square", "chatBubble": L + "message-square", "chatBubble-question": L + "message-circle-question-mark",
    "check": L + "check", "check-square": L + "square-check", "checks": L + "check-check", "chef-hat": L + "chef-hat", "chess-king": L + "crown", "chess-tower": L + "castle",
    "chevron-circle-down": L + "circle-chevron-down", "chevron-circle-left": L + "circle-chevron-left", "chevron-circle-right": L + "circle-chevron-right", "chevron-circle-up": L + "circle-chevron-up",
    "chevron-down": L + "chevron-down", "chevron-down-small": L + "chevron-down", "chevron-left": L + "chevron-left", "chevron-left-small": L + "chevron-left",
    "chevron-right": L + "chevron-right", "chevron-right-small": L + "chevron-right", "chevron-up": L + "chevron-up", "chevron-up-small": L + "chevron-up",
    "chevrons-down": L + "chevrons-down", "chevrons-down-up": L + "chevrons-down-up", "chevrons-left": L + "chevrons-left", "chevrons-left-right": L + "chevrons-left-right",
    "chevrons-right": L + "chevrons-right", "chevrons-right-dotted-left": L + "chevrons-left-right-ellipsis", "chevrons-up": L + "chevrons-up", "chevrons-up-down": L + "chevrons-up-down",
    "chip": L + "cpu", "chip-simple": L + "cpu", "circle": L + "circle", "circle-circle": L + "circle-dot", "circles": L + "blend", "circles-check": L + "circle-check-big",
    "clipboard": L + "clipboard", "clock": L + "clock", "cloud": L + "cloud", "cloud-download": L + "cloud-download", "cloud-upload": L + "cloud-upload",
    "code": L + "code", "code-brackets": L + "brackets", "code-simple": L + "code-xml", "cog": L + "settings", "collection": L + "package-open", "collection-plus": L + "package-plus",
    "color-mode": L + "contrast", "command": L + "command", "comment": L + "message-square", "comment-dashed": L + "message-square-dashed", "comment-dot": L + "message-square-dot",
    "comments": L + "messages-square", "compass": L + "compass", "compass-check": L + "compass", "compass-dot": L + "compass", "cookie": L + "cookie", "copy": L + "copy",
    "corners-in": L + "minimize", "corners-out": L + "maximize", "corners-out-check": L + "scan-search", "corners-out-sparkle": L + "scan-eye",
    "cost-high": L + "banknote", "cost-low": L + "dollar-sign", "cost-medium": L + "coins", "credit-card": L + "credit-card", "cross-medical": L + "cross", "crosshair": L + "crosshair",
    "crown": L + "crown", "crystal-ball": L + "orbit", "cube": L + "box", "cube-coordinates": L + "axis-3d", "cube-nodes": L + "network", "cube-transparent": L + "cuboid",
    "currency-btc": L + "bitcoin", "currency-dollar": L + "dollar-sign", "currency-eth": L + "diamond", "cursor-logo": C + "cube", "cursor-text": L + "text-cursor",
    "cutlery": L + "utensils", "cylinder": L + "cylinder",
    # --- D-F
    "dashboard": L + "layout-dashboard", "database": L + "database", "database-network": L + "database-zap", "debug-stop": L + "circle-stop", "deckchair-umbrella": L + "tree-palm",
    "diagram": L + "workflow", "diff": L + "columns-2", "diff-added": L + "square-plus", "diff-ignored": L + "square-slash", "diff-modified": L + "square-dot",
    "diff-multiple": L + "file-diff", "diff-removed": L + "square-minus", "diff-renamed": L + "square-arrow-right", "diff-single": L + "file-diff",
    "diff-single-arrow-right-up": L + "file-symlink", "diff-single-dot": L + "file-diff",
    "display-check": L + "monitor-check", "display-circle": L + "monitor-dot", "display-connect": L + "monitor-up", "display-play": L + "monitor-play",
    "display-waves": L + "monitor-speaker", "displays": L + "monitor", "drop": L + "droplet", "easel": L + "presentation", "edit": L + "pencil", "elephant": L + "paw-print",
    "envelope": L + "mail", "envelope-open": L + "mail-open", "eraser": L + "eraser", "error": L + "circle-x", "execution-parallel": L + "chevrons-right",
    "execution-sequential": L + "arrow-right", "extensions": L + "blocks", "eye": L + "eye", "eye-closed": L + "eye-closed", "eye-slash": L + "eye-off",
    "fast-backward": L + "rewind", "fast-forward": L + "fast-forward", "feedback": L + "message-square-more", "file": L + "file", "file-add": L + "file-plus",
    "file-arrow-right-up": L + "file-symlink", "file-chevrons-left-right": L + "file-code", "file-image": L + "file-image", "file-list": L + "file-text", "file-lock": L + "file-lock",
    "file-pdf": L + "file-text!", "file-text": L + "file-text", "files": L + "files", "film-reel": L + "clapperboard", "film-strip": L + "film", "filter": L + "funnel",
    "flag": L + "flag", "flag-hill": L + "flag-triangle-right", "flame": L + "flame", "floppy-disc": L + "save", "focus-window": L + "picture-in-picture-2",
    "fold-dashed": L + "fold-vertical", "folder": L + "folder", "folder-arrow-right-up": L + "folder-symlink", "folder-check": L + "folder-check", "folder-dashed": L + "folder-dot",
    "folder-library": L + "folder-archive", "folder-open": L + "folder-open", "folders": L + "folders", "fork": L + "split",
    # --- G-L
    "game-controller": L + "gamepad-2", "game-controller-retro": L + "gamepad", "gauge": L + "gauge", "gem": L + "gem", "gift": L + "gift",
    "git-branch": L + "git-branch", "git-commit": L + "git-commit-vertical", "git-commit-horizontal": L + "git-commit-horizontal", "git-compare": L + "git-compare",
    "git-fetch": L + "arrow-down-to-dot", "git-fork": L + "git-fork", "git-merge": L + "git-merge", "git-pull": L + "git-pull-request-arrow", "git-pull-request": L + "git-pull-request",
    "git-pull-request-closed": L + "git-pull-request-closed", "git-pull-request-create": L + "git-pull-request-create", "git-pull-request-done": L + "git-merge",
    "git-pull-request-draft": L + "git-pull-request-draft", "git-push": L + "arrow-up-from-dot", "github": S + "github", "github-actions": S + "githubactions",
    "globe": L + "globe", "graduation-cap": L + "graduation-cap", "graph-line": L + "chart-line", "grid": L + "layout-grid", "grid-plus": L + "grid-2x2-plus", "grid-sparkle": L + "grid-2x2-check",
    "gripper": L + "grip-vertical", "hamburger": L + "hamburger", "hammer": L + "hammer", "hash": L + "hash", "hat": L + "hard-hat", "headphones": L + "headphones", "headset": L + "headset",
    "heart": L + "heart", "hexagon": L + "hexagon", "history": L + "history", "home": L + "house", "hourglass": L + "hourglass", "image": L + "image", "image-square": L + "image",
    "infinity": L + "infinity", "info": L + "info", "inspect": L + "square-mouse-pointer", "issue": L + "circle-dot", "issue-draft": L + "circle-dashed", "issues": L + "circle-alert",
    "joystick": L + "joystick", "kebab-vertical": L + "ellipsis-vertical", "key": L + "key", "keyboard": L + "keyboard", "keyboard-tab": L + "arrow-right-to-line", "laptop": L + "laptop",
    "layout-dialog": L + "app-window", "layout-empty": L + "rectangle-horizontal", "layout-floating-window": L + "picture-in-picture",
    "layout-panel-bottom-dock": L + "panel-bottom-dashed", "layout-panel-bottom-on": L + "panel-bottom", "layout-panel-bottom-undock": L + "panel-bottom-open", "layout-panel-off": L + "panel-bottom-close",
    "layout-sidebar-left": L + "panel-left", "layout-sidebar-left-dock": L + "panel-left-dashed", "layout-sidebar-left-on": L + "panel-left-close", "layout-sidebar-left-undock": L + "panel-left-open",
    "layout-sidebar-right": L + "panel-right", "layout-sidebar-right-dock": L + "panel-right-dashed", "layout-sidebar-right-on": L + "panel-right-close", "layout-sidebar-right-undock": L + "panel-right-open",
    "layout-split-horizontal-dashed": L + "panel-left-right-dashed", "layout-split-horizontal-right-dock": L + "panel-right-dashed", "layout-split-horizontal-right-undock": L + "panel-right-open",
    "leaf": L + "leaf", "lego": L + "toy-brick", "library": L + "library", "lightbulb": L + "lightbulb", "lightning": L + "zap", "link": L + "link",
    "list-bullets": L + "list", "list-checks": L + "list-checks", "list-filter": L + "list-filter", "list-ordered": L + "list-ordered", "list-todo": L + "list-todo",
    "list-todo-subtask": L + "list-tree", "list-x": L + "list-x", "loading": L + "loader", "lock": L + "lock",
    # --- Brand and product marks (Simple Icons where the mark is carried; neutral stand-ins otherwise)
    "logo-azure": L + "cloud!", "logo-azure-devops": L + "infinity!", "logo-figma": S + "figma", "logo-gitlab": S + "gitlab", "logo-jira": S + "jira", "logo-linear": S + "linear",
    "logo-markdown": S + "markdown", "logo-microsoft-teams": L + "users!", "logo-notion": S + "notion", "logo-python": S + "python", "logo-sentry": S + "sentry",
    "logo-slack": L + "hash!", "logo-vscode": L + "code-xml!", "logo-vscode-insiders": L + "code-xml!", "logo-x": S + "x",
    # --- M-P
    "mac-mini": L + "hard-drive", "magic-wand": L + "wand-sparkles", "magnet": L + "magnet", "magnifying-glass-fuzzy": L + "text-search", "magnifying-glass-sparkle": L + "search-check",
    "map": L + "map", "map-pin": L + "map-pin", "markdown": S + "markdown", "mask-happy": L + "venetian-mask", "masks-happy": L + "drama", "mcp": S + "modelcontextprotocol",
    "megaphone": L + "megaphone", "menu": L + "menu", "merge": L + "merge", "mic": L + "mic", "minus": L + "minus", "minus-circle": L + "circle-minus", "minus-small": L + "minus",
    "mobile": L + "smartphone", "moon": L + "moon", "moon-sparkle": L + "moon-star", "moon-z": L + "bed", "more": L + "ellipsis", "music": L + "music", "mute": L + "volume-x",
    "new-folder": L + "folder-plus", "newspaper": L + "newspaper", "note": L + "sticky-note", "one-circle": L + "circle-dot", "owl": L + "bird", "package": L + "package",
    "paint-roller": L + "paint-roller", "palette": L + "palette", "paperclip": L + "paperclip", "paperplane": L + "send", "paragraph": L + "text", "pass": L + "circle-check",
    "pause": L + "pause", "pause-circle": L + "circle-pause", "paw": L + "paw-print", "pen-nib": L + "pen-tool", "pencil-square": L + "square-pen", "pentagon": L + "pentagon",
    "people": L + "users", "people-3": L + "users-round", "percent": L + "percent", "person": L + "user", "person-add": L + "user-plus", "person-chat-bubble": L + "speech",
    "piano": L + "piano", "pilcrow": L + "pilcrow", "pin": L + "pin", "pin-slash": L + "pin-off", "pipe": L + "cable", "plan": L + "route", "plane": L + "plane",
    "play-bug": L + "bug-play", "play-circle": L + "circle-play", "play-slow": L + "play", "play-super-fast": L + "fast-forward", "playback-loop": L + "repeat", "plays-bug": L + "bug-play",
    "plug": L + "plug", "plug-slash": L + "unplug", "plus-circle": L + "circle-plus", "plus-minus": L + "diff", "pointer-arrow": L + "mouse-pointer-2", "pug": L + "dog",
    "pulse": L + "activity", "puzzle-piece": L + "puzzle",
    # --- Q-S
    "question": L + "circle-question-mark", "question-circle": L + "circle-question-mark", "quote": L + "quote", "radar": L + "radar", "radio-tower": L + "radio-tower",
    "redo": L + "rotate-cw", "regex": L + "regex", "remote-control": L + "radio-receiver", "replace": L + "replace", "report": L + "message-square-warning", "return": L + "corner-down-left",
    "review": L + "circle-dashed", "robot": L + "bot", "rocket": L + "rocket", "rocking-chair": L + "rocking-chair", "rss": L + "rss", "ruler": L + "ruler", "run": L + "play",
    "satellite": L + "satellite", "scales": L + "scale", "seal": L + "badge", "search": L + "search", "search-stop": L + "search-slash", "server": L + "server", "servers": L + "server",
    "shapes-square-circle": L + "shapes", "share": L + "share", "shield": L + "shield", "shield-check": L + "shield-check", "shield-question": L + "shield-question", "shield-x": L + "shield-x",
    "shoe-fast": L + "sport-shoe", "shopping-bag": L + "shopping-bag", "shopping-basket": L + "shopping-basket", "signal": L + "radio", "slash-circle": L + "ban",
    "sliders": L + "sliders-horizontal", "smartwatch": L + "watch", "smiley-happy": L + "smile", "smiley-happy-square": L + "laugh", "smiley-neutral": L + "meh", "smiley-plus": L + "smile-plus",
    "smiley-sad": L + "frown", "snowflake": L + "snowflake", "soccer-ball": L + "volleyball", "sort-ascending": L + "arrow-down-narrow-wide", "sort-descending": L + "arrow-up-wide-narrow",
    "sparkle": L + "sparkles", "speaker-hifi": L + "speaker", "split": L + "split", "split-horizontal": L + "columns-2", "split-vertical": L + "rows-2", "sprint": L + "footprints",
    "square": L + "square", "square-dashed": L + "square-dashed", "square-dot": L + "square-dot", "squares": L + "copy", "squares-minus": L + "copy-minus", "squares-plus": L + "copy-plus",
    "squares-x": L + "copy-x", "stack": L + "layers", "star": L + "star", "star-full": L + "star#fill", "status-draft": L + "circle-dashed", "stop": L + "square",
    "stopwatch": L + "timer", "storefront": L + "store", "sun": L + "sun", "swatches": L + "swatch-book", "sync": L + "refresh-cw",
    # --- T-Z
    "t-shirt": L + "shirt", "table": L + "table", "tabs": L + "panels-top-left", "tag": L + "tag", "tags-chevron-down": L + "chevrons-down", "tags-chevron-left": L + "chevrons-left",
    "tags-chevron-right": L + "chevrons-right", "tags-chevron-up": L + "chevrons-up", "target": L + "target", "terminal": L + "terminal", "terminal-rectangle": L + "square-terminal",
    "text-aa": L + "case-sensitive", "text-ab": L + "case-upper", "text-b": L + "bold", "text-c": L + "type!", "text-d": L + "type!", "text-italic": L + "italic", "text-j": L + "type!",
    "text-r": L + "type!", "text-s": L + "type!", "text-strikethrough": L + "strikethrough", "text-t": L + "type", "text-t-square": L + "type-outline", "text-tt": L + "type", "text-y": L + "type!",
    "thinking-high": L + "brain-cog", "thinking-low": L + "brain", "thinking-medium": L + "brain-circuit", "threads-parallel": L + "align-justify", "threads-single": L + "minus",
    "three-bars": L + "menu", "thumbsdown": L + "thumbs-down", "thumbsup": L + "thumbs-up", "trafficCone": L + "traffic-cone", "trash": L + "trash-2", "tray": L + "inbox",
    "treasure-chest": L + "package-open", "trending-down": L + "trending-down", "trending-up": L + "trending-up",
    "triangle-small-down": L + "triangle#fill@180", "triangle-small-left": L + "triangle#fill@270", "triangle-small-right": L + "triangle#fill@90", "triangle-small-up": L + "triangle#fill",
    "twig": L + "sprout", "unfold-dashed": L + "unfold-vertical", "unlock": L + "lock-open", "unmute": L + "volume-2", "unverified": L + "badge-question-mark", "vault": L + "vault",
    "verified": L + "badge-check", "versions": L + "layers-2", "video-camera": L + "video", "vm": L + "monitor", "vr": L + "glasses", "vr-headset": L + "glasses", "vr-headset-head": L + "hat-glasses",
    "wallet": L + "wallet", "watch": L + "watch", "waveform": L + "audio-waveform", "whole-word": L + "whole-word", "window": L + "app-window", "windows": L + "app-window-mac",
    "wrench": L + "wrench", "x": L + "x", "yarn": S + "yarn", "zipper": L + "file-archive", "zoom-in": L + "zoom-in", "zoom-out": L + "zoom-out", "zzz": L + "bed",
    # --- File types (language and tool marks)
    "file-type-adobe-illustrator": L + "pen-tool!", "file-type-adobe-photoshop": L + "image!", "file-type-babel": S + "babel", "file-type-bazel": S + "bazel", "file-type-bevy": S + "bevy",
    "file-type-bicep": L + "file-code!", "file-type-biomejs": S + "biome", "file-type-bower": S + "bower", "file-type-bun": S + "bun", "file-type-c-plus-plus": S + "cplusplus",
    "file-type-c-sharp": L + "file-code!", "file-type-clojure": S + "clojure", "file-type-crystal": S + "crystal", "file-type-cuda": L + "cpu!", "file-type-dart": S + "dart",
    "file-type-docker": S + "docker", "file-type-ejs": S + "ejs", "file-type-elixir": S + "elixir", "file-type-eslint": S + "eslint", "file-type-f-sharp": S + "fsharp",
    "file-type-firebase": S + "firebase", "file-type-geckodriver": L + "file-code!", "file-type-git-meta": S + "git", "file-type-go": S + "go", "file-type-godot": S + "godotengine",
    "file-type-grails": L + "file-code!", "file-type-graphql": S + "graphql", "file-type-groovy": S + "apachegroovy", "file-type-grunt": S + "grunt", "file-type-gulp": S + "gulp",
    "file-type-haml": L + "file-code!", "file-type-handlebars": S + "handlebarsdotjs", "file-type-haskell": S + "haskell", "file-type-ionic": S + "ionic", "file-type-java": L + "coffee!",
    "file-type-javascript": S + "javascript", "file-type-julia": S + "julia", "file-type-jupyter": S + "jupyter", "file-type-karma": L + "test-tube!", "file-type-kotlin": S + "kotlin",
    "file-type-latex": S + "latex", "file-type-liquid": L + "droplet!", "file-type-maven": S + "apachemaven", "file-type-mustache": L + "file-code!", "file-type-npm": S + "npm",
    "file-type-nunjucks": S + "nunjucks", "file-type-ocaml": S + "ocaml", "file-type-odata": L + "database!", "file-type-pdf": L + "file-text!", "file-type-perl": S + "perl",
    "file-type-platformio": S + "platformio", "file-type-powershell": L + "square-terminal!", "file-type-prettier": S + "prettier", "file-type-prisma": S + "prisma",
    "file-type-prolog": L + "file-code!", "file-type-puppet": S + "puppet", "file-type-python": S + "python", "file-type-reason": S + "reason", "file-type-rescript": S + "rescript",
    "file-type-rollup": S + "rollupdotjs", "file-type-rust": S + "rust", "file-type-sass": S + "sass", "file-type-sbt": L + "file-code!", "file-type-scala": S + "scala",
    "file-type-slim": L + "file-code!", "file-type-stylus": S + "stylus", "file-type-sublime": S + "sublimetext", "file-type-svelte": S + "svelte", "file-type-swift": S + "swift",
    "file-type-terraform": S + "terraform", "file-type-typescript": S + "typescript", "file-type-vala": S + "vala", "file-type-vite": S + "vite", "file-type-vsc": L + "code-xml!",
    "file-type-vue": S + "vuedotjs", "file-type-web-assembly": S + "webassembly", "file-type-webpack": S + "webpack", "file-type-windows": L + "grid-2x2!", "file-type-yarn": S + "yarn",
    "file-type-zig": S + "zig",
}

# Labels for ids whose name does not read well when merely humanised. Simple Icons titles cover the rest of the brands.
LABELS: dict[str, str] = {
    "logo-azure": "Azure", "logo-azure-devops": "Azure DevOps", "logo-microsoft-teams": "Microsoft Teams", "logo-slack": "Slack",
    "logo-vscode": "VS Code", "logo-vscode-insiders": "VS Code Insiders", "cursor-logo": "Cursor", "bugbot": "Bugbot", "mcp": "MCP", "mac-mini": "Mac mini",
    "file-type-adobe-illustrator": "Adobe Illustrator", "file-type-adobe-photoshop": "Adobe Photoshop", "file-type-bicep": "Bicep", "file-type-c-sharp": "C#",
    "file-type-cuda": "CUDA", "file-type-geckodriver": "geckodriver", "file-type-grails": "Grails", "file-type-haml": "Haml", "file-type-java": "Java",
    "file-type-karma": "Karma", "file-type-liquid": "Liquid", "file-type-mustache": "Mustache", "file-type-odata": "OData", "file-type-pdf": "PDF", "file-pdf": "PDF file",
    "file-type-powershell": "PowerShell", "file-type-prolog": "Prolog", "file-type-sbt": "sbt", "file-type-slim": "Slim", "file-type-vsc": "VS Code file", "file-type-windows": "Windows",
    "file-type-git-meta": "Git", "chatBubble": "Chat bubble", "chatBubble-question": "Chat bubble question", "trafficCone": "Traffic cone", "t-shirt": "T-shirt",
    "currency-btc": "Bitcoin", "currency-eth": "Ether", "currency-dollar": "Dollar", "cd": "CD", "vm": "VM", "vr": "VR", "vr-headset": "VR headset", "vr-headset-head": "VR headset on",
    "rss": "RSS", "x": "X", "at": "At sign", "zzz": "Zzz", "text-aa": "Text Aa", "text-ab": "Text AB", "text-b": "Text B", "text-c": "Text C", "text-d": "Text D", "text-j": "Text J",
    "text-r": "Text R", "text-s": "Text S", "text-t": "Text T", "text-tt": "Text Tt", "text-y": "Text Y", "text-t-square": "Text T square", "one-circle": "One in a circle",
}

# Picker sections, first match wins: (label, explicit ids, id prefixes). Whatever matches nothing lands in the last one.
GROUPS: list[tuple[str, set[str], tuple[str, ...]]] = [
    ("Brands & tools", {"github", "github-actions", "mcp", "markdown", "yarn", "cursor-logo", "bugbot"}, ("logo-", "file-type-")),
    ("Arrows", {"redo", "return", "sync", "keyboard-tab", "merge", "split", "fork", "playback-loop", "history", "execution-parallel", "execution-sequential", "pointer-arrow"},
     ("arrow", "chevron", "corners-", "sort-", "trending-", "triangle-small-", "tags-chevron-", "fold-", "unfold-")),
    ("Code & git", {"code", "code-brackets", "code-simple", "terminal", "terminal-rectangle", "bug", "brackets-curly", "bracket-dot", "bracket-error", "binary", "regex", "whole-word",
                    "replace", "extensions", "plug", "plug-slash", "debug-stop", "play-bug", "plays-bug", "inspect", "run", "cursor-text", "package", "zipper", "chip", "chip-simple",
                    "cube-nodes", "database", "database-network", "server", "servers", "cylinder", "vm", "cloud", "cloud-download", "cloud-upload", "versions", "stack", "command", "hash",
                    "at", "asterisk", "pipe", "plan", "threads-parallel", "threads-single", "brain", "brain-hourglass", "brain-simple", "brain-simplest", "brain-slash", "thinking-high",
                    "thinking-low", "thinking-medium", "robot", "agent", "agent-circle", "agent-square", "agents", "agents-swarm", "sparkle", "magic-wand", "diagram", "cog", "sliders",
                    "wrench", "hammer", "ruler", "key", "lock", "unlock", "shield", "shield-check", "shield-question", "shield-x"},
     ("git-", "diff", "layout-", "display")),
    ("Symbols & status", {"check", "check-square", "checks", "error", "info", "question", "question-circle", "alert", "pass", "status-draft", "issue", "issue-draft", "issues", "review",
                          "verified", "unverified", "seal", "one-circle", "plus-minus", "minus", "minus-circle", "minus-small", "add", "plus-circle", "slash-circle", "color-mode",
                          "infinity", "percent", "more", "kebab-vertical", "gripper", "menu", "three-bars", "loading", "x", "pin", "pin-slash", "tag", "link", "eye", "eye-closed",
                          "eye-slash", "trash", "hexagon", "pentagon", "shapes-square-circle", "cube", "cube-coordinates", "cube-transparent", "lightning", "star", "star-full"},
     ("circle", "square")),
    ("Files & folders", {"archive", "clipboard", "note", "newspaper", "book", "book-open", "bookmark", "library", "collection", "collection-plus", "tray", "paperclip", "board-kanban",
                         "table", "dashboard", "grid", "grid-plus", "grid-sparkle", "window", "windows", "browser", "browsers", "tabs", "copy", "floppy-disc", "filter", "search",
                         "search-stop", "magnifying-glass-fuzzy", "magnifying-glass-sparkle", "zoom-in", "zoom-out", "eraser", "edit", "pencil-square", "pen-nib", "paragraph", "pilcrow",
                         "quote", "focus-window", "binoculars"},
     ("file", "folder", "new-folder", "list-", "text-")),
    ("Media", {"image", "image-square", "film-reel", "film-strip", "camera", "video-camera", "music", "piano", "headphones", "headset", "mic", "mute", "unmute", "speaker-hifi", "waveform",
               "cd", "play-circle", "play-slow", "play-super-fast", "pause", "pause-circle", "fast-backward", "fast-forward", "stop", "radio-tower", "signal", "rss", "palette", "brush",
               "paint-roller", "swatches", "easel", "game-controller", "game-controller-retro", "joystick", "remote-control"}, ()),
    ("Chat & people", {"account", "person", "person-add", "person-chat-bubble", "people", "people-3", "smiley-happy", "smiley-happy-square", "smiley-neutral", "smiley-plus", "smiley-sad",
                       "mask-happy", "masks-happy", "thumbsup", "thumbsdown", "heart", "feedback", "report", "megaphone", "envelope", "envelope-open", "paperplane", "bell", "bell-dot",
                       "bell-slash", "share", "crown", "chess-king", "graduation-cap", "hat", "chef-hat", "t-shirt", "bowtie"},
     ("chat", "comment")),
    ("Devices & places", {"laptop", "mobile", "mac-mini", "keyboard", "smartwatch", "watch", "vr", "vr-headset", "vr-headset-head", "satellite", "radar", "bluetooth", "calculator",
                          "home", "building", "buildings", "castle", "chess-tower", "storefront", "army-base", "beach-umbrella", "deckchair-umbrella", "rocking-chair", "beehouse",
                          "vault", "treasure-chest", "map", "map-pin", "compass", "compass-check", "compass-dot", "globe", "flag", "flag-hill", "plane", "car", "rocket", "crosshair",
                          "target", "lego", "briefcase", "shopping-bag", "shopping-basket", "gift", "cutlery", "hamburger", "cookie"}, ()),
    ("Time & money", {"clock", "alarm-clock", "stopwatch", "hourglass", "calendar", "calendar-hourglass", "moon-z", "zzz", "banknote", "banknotes-stack", "cardholder", "credit-card",
                      "wallet", "currency-btc", "currency-dollar", "currency-eth", "cost-high", "cost-low", "cost-medium", "scales", "chart-bars", "chart-pie", "chart-pyramid",
                      "chart-scatter", "graph-line", "gauge", "pulse"}, ()),
    ("Nature & objects", set(), ()),
]

STROKE_WEIGHT = 1.75  # the app draws its Lucide glyphs at this width (CursorIcons.kt), so the catalog matches


def humanise(name: str) -> str:
    text = name
    for prefix in ("logo-", "file-type-"):
        if text.startswith(prefix):
            text = text[len(prefix):]
    text = re.sub(r"([a-z])([A-Z])", r"\1 \2", text).replace("-", " ").strip()
    return text[:1].upper() + text[1:].lower()


def fmt(value: float) -> str:
    text = ("%.3f" % value).rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def lucide_paths(svg: str) -> list[str]:
    """Every drawable element of a Lucide SVG as path data; Compose's PathParser draws the arcs."""
    paths: list[str] = []
    for tag, attrs in re.findall(r"<(path|circle|rect|line|polyline|polygon|ellipse)\b([^>]*)/?>", svg):
        a = dict(re.findall(r'([a-zA-Z0-9-]+)="([^"]*)"', attrs))
        f = lambda key, default="0": float(a.get(key, default))  # noqa: E731
        if tag == "path":
            paths.append(re.sub(r"\s+", " ", a["d"].strip()))
        elif tag == "circle":
            cx, cy, r = f("cx"), f("cy"), f("r")
            paths.append(f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0")
        elif tag == "ellipse":
            cx, cy, rx, ry = f("cx"), f("cy"), f("rx"), f("ry")
            paths.append(f"M{fmt(cx - rx)} {fmt(cy)}a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(2 * rx)} 0a{fmt(rx)} {fmt(ry)} 0 1 0 {fmt(-2 * rx)} 0")
        elif tag == "rect":
            x, y, w, h = f("x"), f("y"), f("width"), f("height")
            rx = f("rx", a.get("ry", "0"))
            ry = f("ry", a.get("rx", "0"))
            if rx == 0 and ry == 0:
                paths.append(f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z")
            else:
                paths.append(
                    f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(ry)}v{fmt(h - 2 * ry)}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(ry)}"
                    f"h{fmt(-(w - 2 * rx))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(-rx)} {fmt(-ry)}v{fmt(-(h - 2 * ry))}a{fmt(rx)} {fmt(ry)} 0 0 1 {fmt(rx)} {fmt(-ry)}z"
                )
        elif tag == "line":
            paths.append(f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}")
        elif tag in ("polyline", "polygon"):
            pts = re.findall(r"-?[\d.]+", a["points"])
            pairs = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts) - 1, 2)]
            paths.append("M" + "L".join(pairs) + ("z" if tag == "polygon" else ""))
    return paths


def simple_icon_path(svg: str) -> str:
    paths = re.findall(r'<path[^>]*\bd="([^"]*)"', svg)
    assert len(paths) == 1, "Simple Icons glyphs are one path"
    return paths[0]


def kotlin_string(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main(lucide_dir: Path, simple_dir: Path) -> None:
    names = json.loads((HERE / "cursor-icon-names.json").read_text())
    picker: list[str] = names["picker"]
    aliases: dict[str, str] = names["aliases"]
    missing = [n for n in picker if n not in MAPPING]
    extra = [n for n in MAPPING if n not in picker]
    if missing or extra:
        sys.exit(f"MAPPING out of step with the picker list: missing {missing}, unexpected {extra}")

    lucide_version = json.loads((lucide_dir / "package.json").read_text())["version"]
    simple_version = json.loads((simple_dir / "package.json").read_text())["version"]
    simple_titles = {icon["slug"]: icon["title"] for icon in json.loads((simple_dir / "data/simple-icons.json").read_text())}

    specs: list[tuple[str, str]] = []  # (id, kotlin expression)
    stand_ins: list[str] = []
    labels: dict[str, str] = {}
    sources = {"lucide": 0, "simple": 0, "cursor": 0}
    for name in picker:
        target = MAPPING[name]
        stand_in = target.endswith("!")
        target = target.rstrip("!")
        rotation = 0
        filled = False
        if "@" in target:
            target, deg = target.split("@")
            rotation = int(deg)
        if target.endswith("#fill"):
            target = target[: -len("#fill")]
            filled = True
        kind, ref = target.split(":", 1)
        if kind == "l":
            svg = (lucide_dir / "icons" / f"{ref}.svg").read_text()
            paths = lucide_paths(svg)
            assert paths, f"{ref}: no drawable elements"
            args = ", ".join(kotlin_string(p) for p in paths)
            if filled and rotation:
                expr = f"filledStroke({rotation}f, {args})"
            elif filled:
                expr = f"filledStroke(0f, {args})"
            else:
                expr = f"stroke({args})"
            sources["lucide"] += 1
        elif kind == "s":
            svg = (simple_dir / "icons" / f"{ref}.svg").read_text()
            expr = f"fill({kotlin_string(simple_icon_path(svg))})"
            labels.setdefault(name, simple_titles[ref])
            sources["simple"] += 1
        elif kind == "c":
            expr = "cursorCube()"
            sources["cursor"] += 1
        else:
            sys.exit(f"unknown source in {target}")
        specs.append((name, expr))
        if stand_in:
            stand_ins.append(name)
        if name in LABELS:
            labels[name] = LABELS[name]

    def group_of(name: str) -> str:
        for label, explicit, prefixes in GROUPS:
            if name in explicit or any(name.startswith(p) for p in prefixes):
                return label
        return GROUPS[-1][0]

    grouped: dict[str, list[str]] = {label: [] for label, _, _ in GROUPS}
    for name in picker:
        grouped[group_of(name)].append(name)
    # Product marks lead the brands section; the long run of language file types follows them.
    grouped[GROUPS[0][0]].sort(key=lambda name: name.startswith("file-type-"))

    chunk = 60
    lines: list[str] = []
    w = lines.append
    w("// GENERATED by scripts/project-icons/generate.py from scripts/project-icons/cursor-icon-names.json - do not edit by hand.")
    w(f"// Glyphs: Lucide {lucide_version} (ISC, app/licenses/ISC_Lucide.txt) and Simple Icons {simple_version} (CC0 1.0, app/licenses/CC0_SimpleIcons.txt).")
    w(f"// {len(picker)} icons: {sources['lucide']} Lucide, {sources['simple']} Simple Icons, {sources['cursor']} of the app's own; {len(stand_ins)} neutral stand-ins for marks no open library carries.")
    w("@file:Suppress(\"LargeClass\", \"LongMethod\", \"MaxLineLength\", \"ktlint\")")
    w("")
    w("package com.cursorforandroid.ui.icons")
    w("")
    w("/** The names the desktop's picker offers, in its order, with what this app draws for each; see [ProjectIcons]. */")
    w("internal object ProjectIconCatalog {")
    w(f"    const val CURSOR_VERSION = \"{names['source'].split(' ')[1]}\"")
    w(f"    const val LUCIDE_VERSION = \"{lucide_version}\"")
    w(f"    const val SIMPLE_ICONS_VERSION = \"{simple_version}\"")
    w("")
    w("    /** Every id the picker offers, in the desktop's order. */")
    w("    val PICKER: Array<String> = arrayOf(")
    for i in range(0, len(picker), 8):
        w("        " + ", ".join(kotlin_string(n) for n in picker[i : i + 8]) + ",")
    w("    )")
    w("")
    w("    /** The other names the account accepts for an icon, each the same glyph as a picker id. */")
    w("    val ALIASES: Map<String, String> = mapOf(")
    alias_items = sorted(aliases.items())
    for i in range(0, len(alias_items), 4):
        w("        " + ", ".join(f"{kotlin_string(a)} to {kotlin_string(c)}" for a, c in alias_items[i : i + 4]) + ",")
    w("    )")
    w("")
    w("    /** Ids drawn with a neutral glyph because the mark they name has no open-licensed rendering. */")
    w("    val STAND_INS: Set<String> = setOf(")
    for i in range(0, len(stand_ins), 6):
        w("        " + ", ".join(kotlin_string(n) for n in stand_ins[i : i + 6]) + ",")
    w("    )")
    w("")
    w("    /** Labels for ids whose humanised name would mislead (brand spellings, initialisms). */")
    w("    val LABELS: Map<String, String> = mapOf(")
    label_items = sorted(labels.items())
    for i in range(0, len(label_items), 4):
        w("        " + ", ".join(f"{kotlin_string(a)} to {kotlin_string(c)}" for a, c in label_items[i : i + 4]) + ",")
    w("    )")
    w("")
    w("    /** The picker's sections, in display order; every picker id appears in exactly one. */")
    w("    val GROUPS: List<ProjectIconGroup> = listOf(")
    for label, _, _ in GROUPS:
        ids = grouped[label]
        w(f"        ProjectIconGroup(")
        w(f"            {kotlin_string(label)},")
        w("            listOf(")
        for i in range(0, len(ids), 8):
            w("                " + ", ".join(kotlin_string(n) for n in ids[i : i + 8]) + ",")
        w("            ),")
        w("        ),")
    w("    )")
    w("")
    w("    /** Builds the id -> spec table; split into parts so no single method outgrows the JVM's limits. */")
    w("    fun specs(): HashMap<String, ProjectIconSpec> {")
    w(f"        val map = HashMap<String, ProjectIconSpec>({len(specs) * 2})")
    parts = (len(specs) + chunk - 1) // chunk
    for p in range(parts):
        w(f"        part{p}(map)")
    w("        return map")
    w("    }")
    for p in range(parts):
        w("")
        w(f"    private fun part{p}(m: HashMap<String, ProjectIconSpec>) {{")
        for name, expr in specs[p * chunk : (p + 1) * chunk]:
            w(f"        m[{kotlin_string(name)}] = {expr}")
        w("    }")
    w("")
    w("    private fun stroke(vararg paths: String) = ProjectIconSpec(ProjectIconSpec.Style.STROKE, paths, 0f)")
    w("    private fun filledStroke(rotation: Float, vararg paths: String) = ProjectIconSpec(ProjectIconSpec.Style.FILLED_STROKE, paths, rotation)")
    w("    private fun fill(path: String) = ProjectIconSpec(ProjectIconSpec.Style.FILL, arrayOf(path), 0f)")
    w("    private fun cursorCube() = ProjectIconSpec(ProjectIconSpec.Style.CURSOR_CUBE, emptyArray(), 0f)")
    w("}")
    w("")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines))
    print(f"wrote {OUT.relative_to(REPO)}: {len(picker)} icons ({sources}), {len(aliases)} aliases, {len(stand_ins)} stand-ins, {OUT.stat().st_size} bytes")
    for label, _, _ in GROUPS:
        print(f"  {label}: {len(grouped[label])}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))
