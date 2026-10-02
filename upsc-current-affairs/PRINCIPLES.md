# Working principles for these Anki patches (set by the user)

1. **Never change Anki without an explicit instruction.** Only produce import files. Nothing is added, edited or deleted in the collection unless the user says so for that specific patch.
2. **No bloat.** Every card must earn its place:
   - Cut number trivia and anything UPSC doesn't ask.
   - Never make two cards test the same fact. If a statement card covers it, drop the cloze.
   - Prefer one exam-format card that tests several traps over several single-fact cards.
   - Prelims: at most about 20 cards per newspaper issue, and fewer is fine.
   - Ethics: a fixed-size bank, one note per theme. New examples replace old ones (re-import with "update existing notes"); they never add cards.
3. **Verify before carding.** Facts not in the source are marked [static] and checked. Errors in the newspaper itself are flagged, not learned.
4. **Tag:** `current_affairs_October_2026` (Anki tags can't contain spaces).
