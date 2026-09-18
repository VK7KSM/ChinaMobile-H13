# H13 Key Router

Version `0.2-no-transmit` is a safety probe for the H13 hardware-key path.
It filters Android `F1`, `F5`, and `F6` key events through an accessibility
service and logs the matching factory broadcasts, including complete M1/M2/P1/P2
down, up, and long actions. It does not send broadcasts, open `/dev/ttyHS0`,
start Zello, or invoke Interphone transmission APIs.

Log tag: `H13KeyRouter`
