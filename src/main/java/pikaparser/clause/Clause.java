package pikaparser.clause;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import pikaparser.grammar.Rule;
import pikaparser.memotable.Match;
import pikaparser.memotable.MemoEntry;
import pikaparser.memotable.MemoKey;
import pikaparser.memotable.MemoTable;

public abstract class Clause {
    public final Clause[] subClauses;
    public String[] subClauseASTNodeLabels;

    /** Rules this clause is a toplevel clause of */
    public List<Rule> rules;

    /** The parent clauses to seed when this clause's match memo at a given position changes. */
    public final Set<Clause> seedParentClauses = new HashSet<>();

    /** If true, the clause can match zero characters. */
    public boolean canMatchZeroChars;

    public String toStringCached;
    public String toStringWithRuleNameCached;

    // -------------------------------------------------------------------------------------------------------------

    protected Clause(Clause... subClauses) {
        this.subClauses = subClauses;
    }

    public void registerRule(Rule rule) {
        if (rules == null) {
            rules = new ArrayList<>();
        }
        rules.add(rule);
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the list of subclause(s) that are "seed clauses" (first clauses that will be matched in the starting
     * position of this clause). Prevents having to evaluate every clause at every position to put a backref into
     * position from the first subclause back to this clause. Overridden only by {@link Longest}, since this
     * evaluates all of its sub-clauses, and {@link First}, since any one of the sub-clauses can match in the first
     * position.
     */
    protected List<Clause> getSeedSubClauses() {
        return subClauses.length == 0 ? Collections.emptyList() : Arrays.asList(subClauses);
    }

    /** For all seed subclauses, add backlink from subclause to this clause. */
    public void backlinkToSeedParentClauses() {
        for (Clause seedSubClause : getSeedSubClauses()) {
            seedSubClause.seedParentClauses.add(this);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Sets {@link #canMatchZeroChars} to true if this clause can match zero characters, i.e. always matches at any
     * input position.
     * 
     * <p>
     * Overridden in subclasses.
     */
    public void testWhetherCanMatchZeroChars() {
    }

    // -------------------------------------------------------------------------------------------------------------

    public static enum MatchDirection {
        BOTTOM_UP, TOP_DOWN;
    }

    /** Match a clause bottom-up at a given start position. */
    public abstract Match match(MatchDirection matchDirection, MemoTable memoTable, MemoKey memoKey, String input,
            Set<MemoEntry> updatedEntries);

    // -------------------------------------------------------------------------------------------------------------

    public String toStringWithRuleNames() {
        if (toStringWithRuleNameCached == null) {
            if (rules != null) {
                StringBuilder buf = new StringBuilder();
                buf.append('(');
                // Add rule names
                buf.append(String.join(", ",
                        rules.stream().map(rule -> rule.ruleName).sorted().collect(Collectors.toList())));
                buf.append(" <- ");
                // Add any AST node labels
                for (int i = 0, j = 0; i < rules.size(); i++) {
                    if (j > 0) {
                        buf.append(", ");
                    }
                    var rule = rules.get(i);
                    if (rule.astNodeLabel != null) {
                        buf.append(rule.astNodeLabel + ":");
                        j++;
                    }
                }
                buf.append(toString());
                buf.append(')');
                toStringWithRuleNameCached = buf.toString();
            } else {
                toStringWithRuleNameCached = toString();
            }
        }
        return toStringWithRuleNameCached;
    }
}
