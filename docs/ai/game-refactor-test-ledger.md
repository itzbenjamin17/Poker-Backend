# Game refactor test ledger

The full suite ran 1,663 tests at the checkpoint before the remaining refactor.

| Change | Executed cases |
| --- | ---: |
| Checkpoint baseline | 1,663 |
| New `BettingRoundTest` cases | +5 |
| Duplicate scheduling challenge cases already in `GameSchedulingTest` | -28 |
| Expected final total | 1,640 |

The seven model challenge suites previously executed 131 cases. Their 103 nonduplicate cases were retained in the corresponding restore, pot, ready, or seating test suite. The scheduling challenge's 21 test methods, including their parameterized inputs, already existed in `GameSchedulingTest`; together they accounted for 28 executed cases.

`GameTest` had 100 executed cases before this work. Its tests exercise the `Game` aggregate rather than calling the extracted collaborators directly. The 100 cases remain as integration coverage: 34 in `GameTest` and 66 split between the betting (29), showdown (30), and seating (7) integration suites. The snapshot recovery challenge in the persistence package is separate from the seven model challenge files and remains in place.
