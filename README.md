# Process Search — EMI

A client-side EMI addon for **Minecraft 1.20.1 / Fabric**, ported from JEI-ProcessSearch for personal use after I started playing for Prominence II: Hasturian Era.

EMI's item list is very good at *"what is this item?"* and has no answer at all for *"what can this
machine make?"*. In a 445-mod pack that second question is the one that matters when you are laying
out automation, and the answer is buried under thousands of near-identical recipes.

This is a port of the NeoForge/JEI version that lives next door in `JEI-ProcessSearch`. The ideas
are the same; almost none of the code is, because the recipe viewer underneath is different.

## The grammar

Four prefixes, one distinct question each. Everything after a prefix has one shape.

| Prefix | Question | Shape | Example |
|---|---|---|---|
| `>` | what **makes** this item? | `process[/property]` | `>mixing`, `>mixing/heat.heated` |
| `<` | what **consumes** this item? | `process[/property]` | `<crushing`, `<assembler/eu.hv` |
| `*` | what **machine runs** this process? | `process` | `*mixing` → Mechanical Mixer, Basin |
| `~` | what **kind of item** is this? | `class` | `~decorative`, `~trim` |

These are parsed inside EMI's own query compiler, so they are not a separate UI and not a separate
search box. They inherit the whole grammar for free:

```
space   AND        >mixing >fluid.in
&       AND        >mixing & >fluid.in
|       OR         >mixing|>crushing
-       NOT        >mixing -~decorative
```

and they compose with EMI's native `@mod`, `#tooltip` and `$tag`.

> Note for anyone coming from JEI: EMI uses `#` for **tooltip** and `$` for **tag**. That is the
> opposite of JEI. EMI also has no `%creativetab`, `^colour` or `&id` — `&` is its AND operator.

### Why `process/property` is one token

The query this was built for is *ingots craftable by a heated mixer*:

```
ingot >mixing/heat.heated
```

Create's heat requirement is a **field on the recipe, not a category** — there is one
`create:mixing` category whether or not a blaze burner sits under the basin — so no amount of
category browsing separates them.

The compound token is not only about readability. Search tokens are intersected **independently**:
each token maps to its own set of items and the sets are AND-ed. So the two-token form
`>mixing >heat.heated` really means *"(made by mixing) AND (made by something heated)"* — an ingot
produced by cold mixing and, separately, by a heated crushing recipe satisfies both and matches
wrongly. A single `>mixing/heat.heated` token cannot be fooled that way.

Bare forms still work, because matching is by substring:

- `>mixing` — matches the compound too, so: anything a mixer makes
- `>heat.heated` — anything made by a heated recipe, whatever the machine
- `>mixing/heat.heated` — exact, no false positives

### One grammar difference from the JEI version

`>"two words"` does **not** work. EMI's tokenizer only honours quotes at the very start of a token,
so `>"mechanical press"` is read as two tokens. This costs nothing in practice: facet tokens are
sanitised to underscores, so the real token is always `mechanical_press`.

## Facet vocabulary

Tokens are derived from whatever mods are installed, so use `/processsearch facets <text>` to
discover them.

**From every category, every mod**

- the category id and its bare path — `>create:mixing`, `>mixing`
- the category's displayed title — `>fan_washing`
- the workstations that run it — `>mechanical_mixer`, `>basin`
- `shapeless`, and `packing` for nugget↔ingot↔block round trips

**From every recipe, every mod** — these are free on EMI, because they fall out of the ingredient
stacks themselves rather than needing a per-mod adapter:

- `fluid.in` · `fluid.out`
- `chance.certain` · `chance.random` — the difference between a crushing recipe you can build a
  ratio around and one you cannot

**From Create, and every mod built on it**

One `instanceof ProcessingRecipe` covers the whole ecosystem — mixing, crushing, milling, pressing,
deploying, item application, spout filling, item draining, sawing, all four fan processes, packing,
compacting — plus Create Crafts & Additions, Create: New Age, Steam 'n' Rails, Copycats+, Create
Questing, Testosterone and Estrogen.

- `heat.none` · `heat.heated` · `heat.superheated`
- `speed.fast` · `speed.normal` · `speed.slow`

**From Modern Industrialization**

MI is the same shape: one `MachineRecipe` class backs every machine it ships, so one source covers
the mod.

- `eu.lv` … `eu.superconductor` — read from `CableTier.allTiers()`, so addon-registered tiers land
  in a real bucket instead of being lumped into the last one
- `speed.*`

**Item classes** (`~`)

| Class | What it catches |
|---|---|
| `~decorative` | Chipped, Chipped Express, Handcrafted, Decorative Blocks, Supplementaries, Twigs, Convenient Decor, Fast Paintings, DarkPaintings, Better Beds, Immersive Lanterns, Connectible Chains |
| `~trim` | AllTheTrims, DynamicTrim, More Armor Trims, BetterTrims — one smithing recipe per material per pattern, this pack's answer to compressed-block noise |
| `~compressed` | nothing by default; the rule is kept for packs that need it |
| `~dye` | nothing by default; now an ordinary [facet rule](#writing-facet-rules), and the old `dyeCategoryIds` / `dyeRecipePatterns` keys still work |

Mostly used negated, to clear the grid:

```
>crafting -~decorative -~trim
```

`~decorative`, `~trim` and `~compressed` are computed from the item's namespace, not from recipes,
so they catch the thousands of furniture blocks that have no interesting recipe at all. `~dye` is
the other kind: it comes from a recipe, and hangs on whatever that recipe produced.

**From a rule file, any mod**

Anything else, without a line of code — see below.

## Writing facet rules

The two hand-written sources above exist because Create's heat condition and MI's EU cost are
*fields*, and reading a field means compiling against the mod. That is a fine trade for two mods
and a bad one for four hundred: each new one would cost a vendored jar, a build change, a
mod-loaded gate and a config toggle — and a *pack* could never add a facet at all, only a mod
release could.

So everything else is data. Drop a `.json` file in `config/processsearch/facet_rules/` and it is
picked up on the next `/processsearch rebuild`. No compile dependency, no jar, no restart.

```json
{
  "rules": [
    {
      "id": "techreborn_powered",
      "categoryNamespace": "techreborn",
      "tokens": ["powered"]
    },
    {
      "id": "big_smelt",
      "recipeClass": "net.minecraft.world.item.crafting.AbstractCookingRecipe",
      "inputCount": { "min": 2 },
      "tokens": ["bulk_cooking"]
    },
    {
      "id": "fan_dye",
      "categoryId": ["create_dragons_plus:fan_coloring"],
      "recipeIdPattern": "_dye$",
      "tokens": ["dye"],
      "itemClasses": ["dye"]
    }
  ]
}
```

Every condition is optional and they are **ANDed**; within one condition a list is an OR. A rule
with no condition at all is refused, because it would tag every recipe in the pack.

| Field | Shape | Matches |
|---|---|---|
| `categoryId` | string or list | the full `namespace:path`, exactly |
| `categoryNamespace` | string or list | every category of that mod |
| `recipeIdPattern` | regex | searched against the recipe id, not anchored |
| `recipeClass` | fully-qualified name | the backing recipe's type |
| `matchSubclasses` | bool, default `true` | walks superclasses *and* interfaces |
| `requiresFluidInput` / `requiresFluidOutput` | bool | `true` demands a fluid, `false` forbids one |
| `inputCount` / `outputCount` | `2` or `{"min":1,"max":3}` | ingredient **slots**, so a tag counts once |

and emits:

| Field | Effect |
|---|---|
| `tokens` | facet tokens, reachable through `>`, `<` and `*` |
| `itemClasses` | `~` classes hung on the recipe's **outputs** |

Tokens go through the same sanitiser as every other facet, so `"Heated Mixing"` becomes
`heated_mixing` and stays typeable — EMI splits its search box on whitespace and `|`.

### Why matching on the class is the useful part

`recipeClass` walks the whole type hierarchy by **name**. One rule naming Create's
`ProcessingRecipe` therefore reaches mixing, crushing, milling, pressing, every fan process, and
every Create addon built on the same base — the same reach `instanceof` gives `CreateFacets`,
without the class ever being on our classpath.

Nothing here reads a field or calls a method on a recipe. That is the deliberate limit: a rule
cannot ask what a recipe's EU cost *is*, only what kind of thing it is. In exchange, a rule written
for a mod you do not have installed cannot throw — the worst it can do is fail to match.

### Precedence

Bundled defaults load first, then `config/processsearch/facet_rules/` in filename order. A rule
reusing an earlier rule's `id` replaces it outright, which is how you override a bundled default
rather than fighting it. Copy the file out of the jar, edit it, keep the ids.

Nothing throws. A malformed file, or one bad rule inside a good file, is named in the log and
skipped; the rest of the file still loads. `/processsearch stats` reports how many rules ended up
active, so a file that failed to parse is visible rather than silent.

The `dyeCategoryIds` and `dyeRecipePatterns` config keys still work — they are translated into
rules at load, so `~dye` is now an ordinary rule rather than a special case in the index build.

## Finding a route

The three prefixes answer lookups — what makes a thing, what consumes it, what machine runs the
process — and the tree extends those one hop at a time. Neither answers the question you actually
have in a pack this size, which is a *path*:

> I have copper. I want a Precision Mechanism. What do I build?

Hover what you have and press **shift+R**. Hover what you want and press it again. The tree opens
showing the chain between them, source at the top, target at the bottom, one machine per step.
Pressing it twice on the same item cancels.

Searching runs from **both ends at once**. A recipe graph branches hard in either direction, so a
one-way search to depth eight is the branching factor to the eighth; meeting in the middle is two
searches to depth four. It always expands whichever frontier is smaller, which matters because the
two ends are rarely alike — an iron ingot is consumed by hundreds of recipes and produced by three.

Routes are shortest-by-step-count, which reads as *fewest machines*. That is the honest default: the
mod is client-only and cannot know what you have unlocked, so it will not pretend to rank by
difficulty.

### The machine filter works differently here

For the tree, `treeIncludedCategories` is opt-in and starts empty, because a walk fans out
exponentially and needs an allowlist to stay finite.

For routes it is a **preference**, not a gate. Equally short routes are broken in favour of the
machines you ticked, but a route may use anything. A targeted search is already bounded by its node
budget, and defaulting a fresh install to "no machines enabled" would mean routing found nothing at
all.

Set `routeRespectCategoryFilter` to make it a hard constraint — *"route using only what I have
built"* — which is the version you want once a pack is underway and you know you have Create but
not Modern Industrialization.

### Exploring off the route

A route is an answer, not a dead end. Clicking a step expands it outward exactly like the tree
does, with the route still on screen as the spine — so you can see what else that intermediate
feeds, or what else could have made it, without losing the path you came for. Right-click still
starts a fresh tree from any node.

The machine a step already uses is not drawn twice when you expand it; the route's own choice stays
as the single recipe that routes.

### When there is no route

Four different things can stop the search, they need different answers, and the mod says which:

| What it says | What it means |
|---|---|
| *no path* | The reachable set was exhausted. Nothing connects them. |
| *too far* | A route may exist, but longer than `routeMaxSteps`. |
| *search too wide* | `routeMaxNodes` was spent first. |
| *took too long* | `routeMaxMillis` was spent first. |

Reporting "no route" when the truth is "not within eight steps" would send you looking for a path
you already have, which is why they are kept apart.

A failed search still opens the screen, because that is where the **Deeper** button is. It retries
with all three budgets raised — four more steps, double the nodes, double the time — and you can
press it again. There is a fixed ceiling it will not pass: retrying is what makes a step cap
survivable, but an unbounded retry is just a hang with extra clicks.

### Why the budget is a clock

`routeMaxSteps` bounds the *answer*: past a handful of machines a route stops being advice.

`routeMaxMillis` bounds the *search*, and it is the one that keeps a hitch off the frame. Steps and
node counts are both poor proxies for cost, because what a single node costs to expand depends
entirely on how tag-heavy the pack is — one recipe taking a large tag hands back dozens of
predecessors at once. Counting items does not see that; the clock does.

## Filtering recipe pages

Filtering the item grid never solved the whole problem: opening the uses of a Mechanical Mixer still
hands you two thousand pages.

Whatever is in EMI's search box now **also filters the recipe pages**. Type `>mixing/heat.heated`,
open an ingot's recipes, and only the heated mixing pages remain. A grey `filtered: 12 of 2043` line
on the recipe screen says so, because pages silently vanishing would read as a broken mod.

There is deliberately no second search box — same box, same grammar, nothing new to learn. Only the
process prefixes apply here (`>`, `<`, `*`); on a recipe page a recipe either carries a facet or it
does not, so `>` and `<` filter identically. Two guards:

- If a caller asked for one specific recipe, that request is never filtered.
- If a filter would empty every category, the full list is shown instead. On EMI this matters more
  than it did on JEI: an empty page map means the recipe screen never opens at all.

Turn it off with `enableRecipePageFilter = false`.

## The process tree

Hover an item in EMI and press `<` or `>`:

- `<` — **what can this be processed into?** Follows outputs forward.
- `>` — **what are all the ways I can produce this?** Follows inputs backward.

That is the mirror of the search prefixes, where `>` means "made by". On the tree the arrow points
the way the chain runs, which is the reading that makes sense once you are looking at a chain rather
than a single item.

You get a pan/zoom graph alternating items and machines, running **vertically** in the direction of
the question: `<` puts the root at the top and grows downward into what it becomes, `>` puts it at
the bottom and grows upward into what makes it.

A machine node is a whole recipe *category*: the Crushing Wheels node stands for all 47 crushing
recipes that take cobblestone, badged with the count, drawn once. Click it for the list of the
actual 47.

### Nothing is followed until you say so

The tree opens showing the item alone, because **machines are opt-in**. Press **Filters** and you get
every machine that touches it, busiest first, with what each contributed; tick in the ones you care
about and the graph rebuilds. Choices are saved.

That sounds backwards until you try the alternative. In a 445-mod pack, "everything except what I
have thought to exclude" is a wall of boxes you then have to dismantle. "Nothing except what I asked
for" is a question you can actually read the answer to.

### Focus and context, not the whole graph

What is drawn is never the whole walk. It is seven layers by default — focus, machines, items,
machines, items, machines, items — plus:

- where you came from: one node up, and a breadcrumb of the full path
- a `+N` chip anywhere a layer budget left something out

Detail decays with distance. The focus's own machines and their items get the full allowance; layers
past that fan out by two and share a per-layer budget, so seven layers is a couple of hundred boxes
rather than the thousands a naive expansion would produce.

The bottom row has nothing hanging off it, so it packs into **3 × 2 blocks** instead of a long
single row, six items in the width of two. Those blocks get a grey backing; items further
up do not, because they are spread by the width of their own subtrees and a backing across them
would be a wide bar with two icons floating in it.

Clicking an item makes it the focus and walks one more hop; the graph underneath keeps everything
already explored, so going back up costs nothing. Depth is unbounded because you only ever pay for
where you are standing.

| | |
|---|---|
| click an item | follow it — it becomes the focus |
| click a machine | list its recipes |
| click a `+N` chip | reveal what it stands for |
| click a leaf item | start a fresh tree there |
| click a breadcrumb | jump back to that step |
| right-click an item | re-root into a fresh graph |
| drag / scroll | pan / zoom |
| `Backspace` / Back | up one step |
| `F` / Fit | frame what is on screen |
| `Home` / Root | back to the start |
| Filters | choose which machines to follow |
| Esc | back to the game |

Zoom out past 55% and the view switches to **compact**: the labels go, the icons grow to fill the
box, and — the part that matters — the boxes themselves shrink from 130 × 20 to a 30 × 30 square, so
the tree narrows by about four times rather than merely getting smaller. Names are one hover away.

**And it keeps going.** The compact box is not a constant — it grows the further out you go:

| zoom | box | icon on screen |
|---|---|---|
| 50% | 32 | 16.0px |
| 30% | 52 | 15.6px |
| 15% | 92 | 13.8px |
| 8% | 156 | 12.5px |

So an icon at the 8% floor is still 12 real pixels, where a fixed box leaves it at two. The gap
between blocks is a quarter of the icon while the icons are small, then **capped at 16px**. The cap
is what makes the far end worth going to: the icons nearly hold their size on screen, so a gap that
kept scaling with them would leave the tree exactly as wide and zooming out would reveal nothing.
Capped, the far end packs into a dense grid of near-touching icons. Snapping once at the threshold and then holding still, which is what it
used to do, meant the whole effect was one step and then nothing.

The exponent is 0.85 — legibility-first. At 1.0 the icon would hold a constant size on screen and
zooming out would stop revealing anything at all; 0.85 is close enough to that edge that the capped
gap is doing most of the work of actually fitting more on screen.

Connectors are drawn at a thickness of `1.25 / zoom` graph units, so they land at about one screen
pixel whatever the zoom. A plain 1px line is 0.3px at 30% zoom, which the rasteriser keeps only
where a pixel centre happens to fall, so it comes out dashed or missing. Node outlines follow the
same rule, and in compact only the focused and hovered nodes get one: a border on every box is noise
at the zoom where the icon is meant to be carrying it.

Each size change relayouts, so the focused node is pinned to the screen position it already had and
everything reflows around it. Sizes are quantised to 4px, so a slow scroll costs a handful of
relayouts across the whole range rather than one per frame.

The tree opens at **1.45 × the scale that would exactly fit**. Pure fit is legible nowhere on a
graph this wide, and overflowing the edges a little costs one drag.

A `+N` chip is sized as a chip rather than a node: 56 px wide, or the icon size in compact.

The recipe list opens as a **panel over the graph**, which stays dimmed behind it, and Back returns
with your pan and zoom untouched. The graph is drawn 250 deep so the panel covers it: item stacks
render 150 deep in their own right and flush at the end of the frame, so a panel drawn at z 0 was
both farther and earlier than every icon behind it and they punched straight through. In the list, click a row for its full ingredients and right-click
to open it in EMI proper. Clicking outside the panel dismisses it.

### The `+N` chips, and the siblings page

Every chip leads somewhere:

| chip | what it opens |
|---|---|
| machines at the focus | shows the rest of them in place |
| machines deeper down | focuses that item, where they fit |
| items under a machine | the **siblings page** |
| siblings of the focus | the **siblings page** for its parent |

The siblings page is a plain scrolling list, and it is the one view with **no limits and no
filters**: everything that machine produces, including what the search excluded and what the layer
budget cut. It is the escape hatch for "the chip is hiding the thing I wanted". Clicking a row
starts a fresh tree rooted there, with Filters open, because a new root is a new question.

### The search box filters it

All three halves are used, and this is what makes a tree tractable in a 445-mod pack at all:

- **Facet tokens decide which recipes are followed.** `>mixing` means only mixing steps.
- **Negated terms remove items outright.** `-~decorative` means those items are never drawn and
  never expanded — not hidden, not greyed, gone.
- **Positive terms highlight and prioritise.** Matching items are tinted, and they are the branches
  drawn deeper when a layer budget has to choose. The search shapes the view; it never shrinks it.

Positive terms deliberately do not prune, and the reason is worth recording. Retention that keeps
only branches *leading to* a match reads fine against a deep pre-walk, but once the walk is lazy
every child is a leaf, so "leads to a match" collapses into "is itself a match" — and searching
`cobblestone` would delete crushing, milling and blasting for producing gravel, sand and stone.
Removal is what a leading `-` is for.

If the query needs the process index and it is not built yet, the header says
`index not ready — filters skipped` rather than claiming a filter it did not apply. That case used
to be silent *and* inverted: with no index, a negated class filter admitted everything.

### Where branches converge

Items are deduplicated by registry key, so a thing reached down two different branches is *one*
node with two parents — the graph is a DAG drawn as a tree. That is what stops cobblestone → stone
→ cobblestone from running forever, but until now it also meant the same item could appear twice on
screen with nothing saying they were the same thing.

An item drawn in more than one place is now outlined in amber and marked `×2`. Hovering any copy
lights up every other copy.

Convergence is the interesting part, not an artifact to hide: an intermediate that several branches
arrive at is the one worth automating first. In compact mode the outline stays on even though labels
are gone, because at that zoom it is the only thing still worth reading.

An item whose other sighting is outside the rows currently drawn says so in its tooltip instead,
since there is nothing on screen to point at.

### It skips the steps that go nowhere

Anvil repairing, grindstone and enchanting hand back an item of the same kind they consumed, which
turns any tool into an endless loop. Rather than naming them, the tree drops **identity recipes** —
those whose outputs are all things they also consume. That is exactly what those three are, and it
catches anything else shaped like them for free. (Because items are keyed by their `Item`, an
enchanted sword and a plain one are the same thing here, which is why enchanting falls out of the
rule rather than needing to be listed.)

A handful of categories that describe an item rather than transform it — fuel, composting, EMI's
own info/tag/ingredient displays — are excluded by default too. **Filters** lists every category the
walk met, busiest first, with what it contributed; tick one back on and the graph rebuilds. Choices
are saved to the config.

### Limits, and why

Two different things are bounded, and they fail differently.

**What is drawn** — `treeViewLayers` (7) rows, with `treeVisibleMachines` (12) and
`treeVisibleItemsPerMachine` (6, which is exactly a 3 × 2 block) around the focus and
`treeVisiblePerLayer` (72) on each layer past that. Raise these if the screen feels sparse. Anything
past them becomes a `+N` chip rather than being hidden, and the chip leads somewhere.

**What is walked** — 32 machines per item and 32 items per machine, with a 6000-node backstop across
the whole session. Raise these if a `+N` chip is hiding something you wanted.

An item already in the graph links back with `↺` instead of branching again, which is what stops
cobblestone → stone → cobblestone running forever.

## What this does *not* do

Exclusion is set subtraction over items. If an item is produced by *both* a trim recipe and an
ordinary one, `-~trim` still excludes it.

Items are keyed by their `Item`, not by `ItemStack`, so NBT variants collapse into one entry. That
is the right trade for automation questions and is why the index stays small.

## When the index builds

Nothing happens until you open EMI. From then it builds a slice per client tick (3 ms by default,
configurable) on the client thread — never on a worker, because EMI reloads recipes on its own
thread and recipe objects are not under any obligation to be safe to touch off-thread.

Search with a prefix before it has finished and you get an empty grid for a moment: EMI compiles and
runs queries on its own daemon thread, so blocking it to finish the build is not an option. Instead
the query flags that it found no index, the build starts on the next tick, and EMI's search is
re-run the moment it lands.

**It only builds once per set of recipes.** EMI reloads on every world join and replaces every
recipe object, but the index holds none of them — only `Item` and `Fluid` registry singletons, which
come from `BuiltInRegistries` and are frozen at startup rather than synced from the server. So
leaving a world keeps the index aside instead of dropping it, and the next join fingerprints the
recipe set: same recipes, same index, no rebuild. Going singleplayer → title screen → a server
running the same pack costs one build, not three.

The fingerprint covers every recipe id, every output stack, the per-category recipe counts, item and
fluid tag membership, and the config generation. It deliberately does *not* flatten tag ingredients
to check inputs — that is the expensive half of a build, and doing it would defeat the point. The
residual gap is a server that changes a recipe's *inputs* under an unchanged id while keeping the
same input count and outputs; `/processsearch rebuild` is the escape hatch, and
`reuseIndexAcrossWorlds: false` turns the whole thing off.

`/processsearch stats` reports the build as *work* time and *elapsed* time separately. Only the
first is a cost — the second is mostly waiting between ticks, and a large gap between them means the
budget is doing its job.

## Commands

```
/processsearch stats            prefixes, hook state, index state, counts, build time
/processsearch facets <text>    discover the searchable tokens
/processsearch gaps             categories that earned no facets beyond their own name
/processsearch compat           whether EMI's internals are still where this build left them
/processsearch rebuild          reload the config, then drop and rebuild the index
```

`gaps` is the counterpart to `facets`: that one says what the pack *can* be asked, this one says
what it cannot. It lists, biggest first, every category whose recipes contributed nothing but the
category's own name — which is precisely the shortlist of mods a [facet rule](#writing-facet-rules)
would be worth writing for. In a pack this size that list is not guessable, so it is measured.

### When EMI moves

This mod hooks EMI **internals**, not published API: the private `EmiApi.setPages`,
`EmiSearch$CompiledQuery.addQuery`, `EmiScreenManager`'s key handling, the tooltip helper. The mixin
config fails soft on purpose — an EMI update must not brick a live pack — and the price of that
choice is that a moved target is completely silent. The prefixes would simply stop matching.

`/processsearch compat` is the answer to *"it stopped working and I don't know why"*. It looks up
every target by exact signature and reports one of three things:

| | |
|---|---|
| `ok` | present, or the hook has actually fired |
| `unproven` | present, but nothing has exercised it yet — usually fine, use the feature and re-run |
| `missing` | gone. The named feature will not work; everything else still will |

The middle state is deliberate. Some hooks cannot be probed at all — calling `keyPressed` to see
whether the injection fires would run EMI's real key handling, and calling `setPages` would open a
screen — so the honest answer is "not seen yet" rather than a guess.

The same probes run as a **unit test against the vendored jar**, so bumping `libs/emi-*.jar` to a
version that moved something fails the build rather than shipping. `fabric.mod.json` is also bounded
to `<1.2.0`: a major EMI bump refuses to load rather than half-working.

`stats` reports whether the search hook actually installed. That matters: the mixin config fails
soft so an EMI update cannot brick a live pack, which means a missed hook would otherwise be
invisible — the prefixes would just silently stop matching.

## Config

**Main menu → Mods → Process Search → the config button**, if the pack has Mod Menu and Cloth Config
— Prominence II has both. Everything below except `treeIncludedCategories` is editable there, in
four groups, and takes effect on close without a restart. `treeIncludedCategories` is missing on
purpose: it is a list of whatever machines the graph in front of you happens to touch, so the
Filters button on the graph is the only place that can offer it meaningfully.

Neither mod is required. The Mod Menu entrypoint only loads when Mod Menu asks for it, and the
button's Cloth classes only resolve when it is clicked, so a pack with one and not the other gets a
dead button rather than a crash on the mod list.

Underneath is `config/processsearch.json`, rewritten on every load so new keys are migrated into an
old file.

A `configVersion` stamp handles the harder case: Gson overwrites a field's default with whatever is
stored, so *raising* a default does nothing for anyone who already has the file. On a version bump
the affected caps are raised to the new floor, and never lowered past a value you chose yourself.

| Key | Default | |
|---|---|---|
| `madeByPrefix` / `usedInPrefix` | `>` / `<` | change if another mod claims one |
| `machineForPrefix` / `itemClassPrefix` | `*` / `~` | |
| `enableCreateFacets` | `true` | heat, speed |
| `enableModernIndustrializationFacets` | `true` | eu tier, speed |
| `enableCatalystFacets` | `true` | powers `*` and machine-name search |
| `enableRecipePageFilter` | `true` | apply the search box to recipe pages |
| `reuseIndexAcrossWorlds` | `true` | keep the index on leaving a world, reuse it if the recipes match |
| `enableProcessTree` | `true` | the `<` / `>` graph screens |
| `treeConsumersKey` / `treeProducersKey` | `shift+comma` / `shift+period` | any Minecraft key name; these are `<` and `>` on a US layout only |
| `treeViewLayers` | `7` | rows drawn including the focus; walk depth derives from it |
| `treeVisibleMachines` / `treeVisibleItemsPerMachine` | `12` / `6` | how much is **drawn** around the focus |
| `treeVisiblePerLayer` | `72` | nodes drawn on each layer past the focus's items |
| `treeMaxProcessesPerItem` / `treeMaxItemsPerProcess` | `32` / `32` | how much the **walk** keeps |
| `treeMaxNodes` | `6000` | safety backstop on the accumulated graph |
| `treeMinZoom` | `0.08` | how far out you can scroll, and how far Fit will go |
| `treeHideIdentityRecipes` | `true` | drop steps that hand back an item they also consumed |
| `treeIncludedCategories` | empty | opt-in machine list, edited by the Filters button |
| `decorativeModIds` | 12 mods | what `~decorative` catches |
| `trimModIds` | 4 mods | what `~trim` catches |
| `compressedModIds` | empty | |
| `dyeCategoryIds` / `dyeRecipePatterns` | empty | what `~dye` catches |
| `excludedCategories` | empty | skip a slow or noisy category |
| `buildTimeBudgetMillisPerTick` | `3` | |

Prefix characters are config-driven because the useful ones are contested. EMI already claims
`@ # $` and uses `- | &` as operators; a prefix set to one of those is refused with a log line
rather than stolen, because silently taking over `#` would turn a config typo into a bug report
about tooltip search being broken.

`excludedCategories` matters in this pack: EMI Loot, EMI Ores, EMI Trades, EMI
Professions and EMI Enchanting all add large synthetic categories with no backing recipe. They index
fine, but they are the first thing to cut if the build feels slow or the facet list feels noisy.

## Building

The compile-time jars are vendored in `libs/` straight from the instance, because this mod hooks EMI
*internals* (`EmiSearch$CompiledQuery.addQuery`, the private `EmiApi.setPages`) and reads Create's
and MI's recipe fields — none of which is published API. Compiling against the exact jar the pack
runs is the only way for an EMI update that moved one of those to fail the build instead of failing
silently in game.

Five jars now: EMI, Create, Modern Industrialization, Mod Menu and Cloth Config. All
`modCompileOnly` — none of them ships inside the mod, and only EMI is a hard dependency. Cloth's
builder API changes shape between major versions, so pinning to the pack's exact 11.1.136 is what
keeps the config screen honest; against a pack on Cloth 12 it would need rebuilding.

```bash
./gradlew build
```

Then copy `build/libs/processsearch-0.3.0.jar` into the pack's `mods/` folder.

Notes on the toolchain, both of which cost an afternoon if you find them the hard way:

- **Fabric Loom 1.13.6**, not the `1.7-SNAPSHOT` every 1.20.1 template still shows. Loom 1.7 calls
  `Problems.forNamespace`, which Gradle 8.14 removed, and dies while applying the plugin.
- The Java **17** toolchain is downloaded by the foojay resolver in `settings.gradle`. Gradle will
  not silently build 1.20.1 with the JDK 21 you happen to have installed.

## How this differs from the JEI version

Worth knowing if you read that codebase first, because three of these are simplifications:

| | JEI 19.x | EMI 1.1.24 |
|---|---|---|
| Recipe ingredients | run the category's layout builder to find out | `EmiRecipe.getInputs()/getOutputs()` are plain lists |
| Underlying recipe | `RecipeHolder.value()` | `EmiRecipe.getBackingRecipe()`, which resolves the id through the vanilla recipe manager |
| Recipe pages | two hooks: `FocusedRecipes` and a static path | one: `EmiApi.setPages` |
| Search prefixes | extensible `char → PrefixInfo` map | `QueryType` is a Java **enum**, so a mixin is the only way in |

The first two are why the four-adapter chain collapsed into "EMI tells us the roles" plus three
small property sources, and why Create and MI need neither reflection nor a mixin of their own.
