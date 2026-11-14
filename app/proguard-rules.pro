# R8 rules for the release build (ADR-021).
#
# Obfuscation is off: the source is public, and readable class and method
# names keep release logs and stack traces useful. R8 still shrinks and
# optimises. HearCopy uses no reflection, so it needs no keep rules of its
# own; every library brings its own consumer rules.
-dontobfuscate
