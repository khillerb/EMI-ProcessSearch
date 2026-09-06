# Changelog

Notable changes, newest first. Versions are the `mod_version` in `gradle.properties`; there
are no tags, so 0.3.0 means "the state the repository was first published in" rather than a
release anyone downloaded.

Entries say *why* where the reason is not obvious from the change, because that is usually
the part worth remembering.

---

## 0.4.0

Everything since the initial commit. The mod went from answering one question — *what makes
this?* — to answering four, each richer than the last, and grew the machinery a public pack
needs: rules other people can write, a compatibility check, and tests.

### Added

**Build plans** (`shift+P` over an item). Every prerequisite for making something, resolved
down to what nothing makes — ores, mob drops, worldgen — plus the machines to build and the
materials to gather, on a **Plan** summary panel.

A route is one thread through a fabric of requirements; a plan is the whole cloth. The
difference is that resolving is an AND/OR problem and routing only ever walked the OR side:
an item is a choice between the recipes that make it, but a recipe is a demand for *all* of
its inputs. Plans stop at raw resources and never at your inventory, so the same plan is the
same twice.

**Route finding** (`shift+R` on what you have, then on what you want). A path between two
items, searched from both ends at once so it can afford to look eight steps deep. Failures
are typed — *no path*, *too far*, *search too wide*, *took too long* — because those need
different answers, and the last three offer a **Deeper** button that retries with raised
budgets rather than telling you to go and edit a config file.

**Data-driven facet rules.** Facets for any mod, from JSON in
`config/processsearch/facet_rules/` — no compile dependency, no vendored jar, no code change.
Rules match on category id or namespace, a regex over the recipe id, fluid presence,
ingredient counts, and the backing recipe's *type name* walked through superclasses and
interfaces, which is what lets one rule cover a whole mod family. Before this, adding a mod
cost six edit points and a release; a pack could not add a facet at all.

**Interchangeable machines are drawn as one block.** Packs offer the same step three or four
ways — a Macerator, Crushing Wheels, somebody's Pulveriser. Machines that take the same
things in and give the same thing out now share one block, three across and three down,
ranked by how many recipes each machine's category holds, with a `+N` chip past nine. Applies
to the tree, routes and plans, because the test is the same in all three.

**`/processsearch gaps`** — categories that earned nothing but their own name, biggest first.
You cannot write a rule for a gap you cannot see, and in a 445-mod pack the gaps are not
guessable.

**`/processsearch compat`** — whether the EMI internals this mod reaches into are still where
it left them. Sixteen targets, three honest states: `ok`, `missing`, and `unproven` for the
hooks that can only be confirmed by firing. The mixins fail soft so an EMI update cannot brick
a live pack, and the price of that is that a moved target is otherwise silent.

**Convergence marking.** An item drawn in more than one place is outlined and marked `×2`,
and hovering one copy lights up the others. In a build plan that is the most useful thing on
the screen: it names what to automate first.

### Changed

- **EMI is pinned to `>=1.1.0 <1.2.0`.** It was unbounded while depending on 1.1.24
  internals, so a major EMI release would have half-worked. Refusing to load is the safer
  failure.
- **`treeVisibleMachines` counts machine *blocks*, not boxes.** Four interchangeable grinders
  cost one, so genuinely different machines are not pushed off the layer to make room for the
  same answer repeated.
- **The direction toggle is hidden on routes and plans**, where it silently rebuilt the root
  as a walk and threw the answer away. Right-click on any node still starts a walk from it.
- **The machine filter is a preference for routes, not a gate.** A tree fans out
  exponentially and needs an allowlist; a route is bounded by its budget, and defaulting a
  fresh install to "no machines" would have found nothing. `routeRespectCategoryFilter` makes
  it a hard constraint.
- **`CategoryFilterPanel` finally has a scrollbar.** It was the only panel without one — the
  panel with the most rows.
- Config version 6 → 10, with migrations; ten new keys covering routes, plans and facet rules.
- README reorganized to follow the mod's actual arc (search → tree → route → plan → authoring
  → operations) rather than the order the features were written in. TUTORIAL gained lessons 8
  and 9.

### Fixed

- **Hovering picked the node underneath.** The graph drew front-to-back and hit-tested in the
  same order, so overlapping boxes selected the wrong one.
- **Long routes truncated silently.** Rows were capped at `treeViewLayers`, itself clamped to
  9, so an eight-step route stopped at step four with nothing saying so.
- **The DAG link marker was computed and never drawn**, so convergence looked like a leaf.
- **Rows sat at a fixed stride**, which only worked while the last layer alone stacked
  anything; a machine block in the middle of a graph reached through the items beneath it.
- The three keys in `en_us.json` that nothing referenced are gone.

### Internal

- **89 tests, from none.** The facet rule matcher and loader, the route search, and the plan
  resolver are all pure functions behind interfaces, so the half of the mod most likely to be
  wrong is the half that needs no game to check. They caught real bugs: `Set.copyOf`
  scrambling written token order, a compatibility probe forcing EMI's static initialiser, and
  two in the plan resolver's handling of loops versus limits.
- `EmiCompatTest` runs the compatibility probes against the vendored EMI jar at build time, so
  bumping `libs/emi-*.jar` to a version that moved something fails the build rather than
  shipping.
- Shared what was duplicated: `PanelScreen` (five panels each had their own border, scrollbar
  and dismissal), `EquivalentMachines`, `Workstations`, `Budgets`, `IdentityRecipes`, and
  `Scan.firstKeyable`.

---

## 0.3.0

First published state, ported from the NeoForge/JEI version.

- Four search prefixes in EMI's own grammar: `>` made by, `<` used in, `*` machine for,
  `~` item class, composing freely with EMI's `@mod`, `#tooltip`, `$tag` and its `-`, `|`,
  `&` operators.
- `process/property` compound tokens, so `>mixing/heat.heated` cannot be fooled the way two
  independent terms can.
- Recipe-page filtering: the search box narrows recipe screens, not just the item grid.
- The process tree: hover an item, press `<` or `>` for the chain outward, with opt-in machine
  filters, focus-and-breadcrumb navigation, `+N` chips, and a compact zoom mode.
- Facet sources for Create (`heat.*`, `speed.*`) and Modern Industrialization (`eu.*`), plus
  the category, machine-name, `fluid.*` and `chance.*` facets every mod gets for free.
- An index built on the client thread under a per-tick budget, fingerprinted so leaving a
  world and rejoining one with the same recipes reuses it.
