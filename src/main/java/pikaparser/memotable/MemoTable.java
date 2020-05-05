package pikaparser.memotable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

import pikaparser.clause.Clause;
import pikaparser.parser.Parser;

/** A memo entry for a specific {@link Clause} at a specific start position. */
public class MemoTable {
    /** A map from clause to startPos to MemoEntry. */
    private Map<Clause, ConcurrentSkipListMap<Integer, MemoEntry>> memoTable = new ConcurrentHashMap<>();

    /** The number of {@link Match} instances created. */
    public final AtomicInteger numMatchObjectsCreated = new AtomicInteger();

    /**
     * The number of {@link Match} instances added to the memo table (some will be overwritten by later matches).
     */
    public final AtomicInteger numMatchObjectsMemoized = new AtomicInteger();

    /**
     * Get the existing {@link MemoEntry} for this clause at the requested start position, or create and return a
     * new empty {@link MemoEntry} if one did not exist.
     * 
     * @param memoKey
     *            The clause and start position to check for a match.
     * @return The existing {@link MemoEntry} for this clause at the requested start position, or a new empty
     *         {@link MemoEntry} if one did not exist.
     */
    private MemoEntry getOrCreateMemoEntry(MemoKey memoKey) {
        // Look up a memo at the start position
        // If there is no ConcurrentSkipListMap for the clause, create one
        var skipList = memoTable.computeIfAbsent(memoKey.clause, clause -> new ConcurrentSkipListMap<>());
        // If there was no memo at the start position, create one.
        var memoEntry = skipList.computeIfAbsent(memoKey.startPos, startPos -> new MemoEntry(memoKey));
        return memoEntry;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * If matchDirection == BOTTOM_UP, get the current best match in the memo table without recursing to child
     * clauses, or create a new memo entry as a placeholder to use even if there is no current match at the
     * requested {@link MemoKey}.
     * 
     * <p>
     * If matchDirection == TOP_DOWN, recurse down through child clauses (standard recursive descent parsing,
     * unmemoized).
     */
    public Match lookUpBestMatch(MemoKey memoKey, String input, MemoKey parentMemoKey,
            Set<MemoEntry> updatedEntries) {
        // Create a new memo entry for non-terminals
        // (Have to add memo entry if terminal does match, since a new match needs to trigger parent clauses.)
        // Get MemoEntry for the MemoKey
        var memoEntry = getOrCreateMemoEntry(memoKey);

        // Record a backref to the parent MemoEntry, so that if the subclause match changes, the changes
        // will propagate to the parent. Don't need to record a backref if parentMemoKey.startPos is the
        // same as memoKey.startPos, since that case is already handled by the backRefs mechanism.
        if (parentMemoKey.startPos != memoKey.startPos) {
            if (Parser.DEBUG) {
                System.out.println("    Adding backref: " + memoEntry.memoKey + " -> " + parentMemoKey);
            }
            memoEntry.backRefs.add(parentMemoKey);
        }

        if (memoEntry.bestMatch != null) {
            // If there is already a memoized best match in the MemoEntry, return it
            return memoEntry.bestMatch;

        } else if (memoKey.clause.canMatchZeroChars) {
            // Special case -- if there is no current best match for the memo, but its clause always matches
            // zero or more characters, return a zero-width match.
            int firstMatchingSubClauseIdx = 0;
            for (int i = 0; i < memoKey.clause.subClauses.length; i++) {
                // The matching subclause is the first subclause that can match zero characters
                // (this works for all PEG operator types)
                if (memoKey.clause.subClauses[i].canMatchZeroChars) {
                    firstMatchingSubClauseIdx = i;
                    break;
                }
            }
            // Don't need to memoize this match, since it is just a placeholder until the real match state is known
            return new Match(memoKey, firstMatchingSubClauseIdx, /* len = */ 0, Match.NO_SUBCLAUSE_MATCHES);
        }

        // No match was found in the memo table
        return null;
    }

    /**
     * Add a new {@link Match} to the memo table. Called when the subclauses of a clause match according to the
     * match criteria for the clause.
     */
    private Match addMatch(MemoKey memoKey, int firstMatchingSubClauseIdx, int len, Match[] subClauseMatches,
            Set<MemoEntry> updatedEntries) {
        // Get or create MemoEntry for the MemoKey
        var memoEntry = getOrCreateMemoEntry(memoKey);

        // Record the new match in the memo entry, and schedule the memo entry to be updated  
        var newMatch = new Match(memoKey, firstMatchingSubClauseIdx, len, subClauseMatches);
        numMatchObjectsCreated.incrementAndGet();
        memoEntry.addNewBestMatch(newMatch, updatedEntries);
        return newMatch;
    }

    /**
     * Add a new non-terminal {@link Match} to the memo table. Called when the subclauses of a clause match
     * according to the match criteria for the clause.
     */
    public Match addNonTerminalMatch(MemoKey memoKey, int firstMatchingSubClauseIdx, Match[] subClauseMatches,
            Set<MemoEntry> updatedEntries) {
        // Find total length of all subclause matches
        var len = 0;
        if (subClauseMatches.length > 0) {
            for (Match subClauseMatch : subClauseMatches) {
                len += subClauseMatch.len;
            }
        }
        return addMatch(memoKey, firstMatchingSubClauseIdx, len, subClauseMatches, updatedEntries);
    }

    /**
     * Add a new terminal {@link Match} to the memo table. Called when the subclauses of a clause match according to
     * the match criteria for the clause.
     */
    public Match addTerminalMatch(MemoKey memoKey, int len, Set<MemoEntry> updatedEntries) {
        return addMatch(memoKey, /* firstMatchingSubClauseIdx = */ 0, len, Match.NO_SUBCLAUSE_MATCHES,
                updatedEntries);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the {@link Match} entries for all nonoverlapping matches of this clause, obtained by greedily matching
     * from the beginning of the string, then looking for the next match after the end of the current match.
     */
    public List<Match> getNonOverlappingMatches(Clause clause) {
        var skipList = memoTable.get(clause);
        if (skipList == null) {
            return Collections.emptyList();
        }
        var firstEntry = skipList.firstEntry();
        var nonoverlappingMatches = new ArrayList<Match>();
        if (firstEntry != null) {
            // If there was at least one memo entry
            for (var ent = firstEntry; ent != null;) {
                var startPos = ent.getKey();
                var memoEntry = ent.getValue();
                if (memoEntry.bestMatch != null) {
                    // Only store matches
                    nonoverlappingMatches.add(memoEntry.bestMatch);
                    // Start looking for a new match in the memo table after the end of the previous match.
                    // Need to consume at least one character per match to avoid getting stuck in an infinite loop,
                    // hence the Math.max(1, X) term. Have to subtract 1, because higherEntry() starts searching
                    // at a position one greater than its parameter value.
                    ent = skipList.higherEntry(startPos + Math.max(1, memoEntry.bestMatch.len) - 1);
                } else {
                    // Move to next MemoEntry
                    ent = skipList.higherEntry(startPos);
                }
            }
        }
        return nonoverlappingMatches;
    }

    /**
     * Get the {@link Match} entries for all nonoverlapping matches of this clause, obtained by greedily matching
     * from the beginning of the string, then looking for the next match after the end of the current match.
     */
    public List<Match> getAllMatches(Clause clause) {
        var skipList = memoTable.get(clause);
        if (skipList == null) {
            return Collections.emptyList();
        }
        var firstEntry = skipList.firstEntry();
        var allMatches = new ArrayList<Match>();
        if (firstEntry != null) {
            // If there was at least one memo entry
            for (var ent = firstEntry; ent != null;) {
                var startPos = ent.getKey();
                var memoEntry = ent.getValue();
                if (memoEntry.bestMatch != null) {
                    // Only store matches
                    allMatches.add(memoEntry.bestMatch);
                }
                // Move to next MemoEntry
                ent = skipList.higherEntry(startPos);
            }
        }
        return allMatches;
    }

    /**
     * Get the {@link Match} entries for all postions where a match was queried, but there was no match.
     */
    public List<Integer> getNonMatchPositions(Clause clause) {
        var skipList = memoTable.get(clause);
        if (skipList == null) {
            return Collections.emptyList();
        }
        var firstEntry = skipList.firstEntry();
        var nonMatches = new ArrayList<Integer>();
        if (firstEntry != null) {
            // If there was at least one memo entry
            for (var ent = firstEntry; ent != null;) {
                var startPos = ent.getKey();
                var memoEntry = ent.getValue();
                if (memoEntry.bestMatch == null) {
                    nonMatches.add(startPos);
                }
                // Move to next MemoEntry
                ent = skipList.higherEntry(startPos);
            }
        }
        return nonMatches;
    }
}
