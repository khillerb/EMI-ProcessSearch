# Process Search (EMI) — Tutorial

How to answer *"what can this machine make?"* in a 445-mod pack.

Everything here is typed into **EMI's normal search box**. There is no second UI to learn.

---

## Install

Drop `processsearch-0.4.0.jar` into `mods/`. Client-side only — servers neither need it nor care.

Then join a world and **open EMI once**. The index builds quietly at 3 ms per tick from that moment.
You do not have to wait for it — search early and the results fill in a beat later.

Check it took:

```
/processsearch stats
```

You want `search hook: installed` and `index: ready`.

Nothing else is required, but if the pack has Mod Menu and Cloth Config — Prominence II has both —
then **Mods → Process Search → config** from the main menu is where every setting in this tutorial
lives, with sliders instead of a JSON file. Changes take effect when you close it.

---

## The 60-second version

| Type this | Get this |
|---|---|
| `>mixing` | everything a mixer can **make** |
| `<mixing` | everything you can **feed** a mixer |
| `*mixing` | the **machines** that do mixing (Mechanical Mixer, Basin) |
| `~decorative` | items that **are** furniture-mod blocks |
| `>mixing/heat.heated` | things a mixer makes **with a blaze burner under it** |
| `>crafting -~trim` | crafting outputs, **minus** the armour-trim flood |

Read the prefixes as questions:

```
>   what MAKES this?
<   what CONSUMES this?
*   what MACHINE does this?
~   what KIND of item is this?
```

Everything after `>` or `<` is `process` or `process/property`. That is the whole grammar.

That is the search half. There is a graph half too, on four keys over a hovered item:

| Press this | Get this |
|---|---|
| `<` | the **tree**: what this can be turned into |
| `>` | the **tree**: everything that makes it |
| `shift+R` | a **route**: press on what you have, then on what you want |
| `shift+P` | a **plan**: everything needed to make it, down to the ore |

Lessons 7 to 9 cover those. Nothing in the graph needs the search box, but it listens to it.

---

## Lesson 1 — what can this machine make?

Open EMI, type:

```
>crushing
```

The item grid is now only things a Crushing Wheel produces. Not recipes — *items*. This is the
question a recipe viewer cannot otherwise answer, because it is organised around "click an item, see
its recipes", and the main idea of the mod is wanting to go the other direction.

The token is the **category name**, and you can use any part of it:

```
>crushing                  the bare category path
>create:crushing           the full id, if two mods collide
>crushing_wheels           the machine's own name also works
```

Not sure of the name? Never guess:

```
/processsearch facets crush
```

That prints every real token containing "crush". This matters more than it sounds — the token for
Create's fan washing is `fan_washing`, not `washing`, and you would have spent five minutes finding
that out by trial.

---

## Lesson 2 — what can I feed it?

Flip the prefix:

```
<crushing
```

Now you get every item that is *valid input* to a crushing recipe. This is the "I have a chest of
this, what can I do with it" question.

Combine with EMI's own `@mod` prefix to scope it:

```
<crushing @techreborn
```

---

## Lesson 3 — the properties, and why they attach with `/`

Here is the problem this was actually built for.

Create's **heat requirement is a field on the recipe, not a category.** There is exactly one
`create:mixing` category whether or not a blaze burner sits under the basin. So no amount of clicking
through EMI separates "mixing" from "mixing that needs heat".

Properties attach to a process with a slash:

```
>mixing/heat.heated
```

*Things a mixer makes, but only with a heated basin.*

### Why not two separate words?

You might try `>mixing >heat.heated`. It looks equivalent and it is **not**.

Every search token is evaluated independently and the results intersected. So that query really
means:

> (items made by mixing) **AND** (items made by anything heated)

An ingot produced by *cold* mixing, and *separately* by a heated crushing recipe, satisfies both
halves and shows up — even though no heated mixer makes it. The compound token is a single fact about
a single recipe, so it cannot be fooled.

**Rule of thumb:** when the property belongs to the *same recipe* as the process, join them with `/`.
Use separate words only when you genuinely mean two independent conditions.

### The property vocabulary

Bare properties work too — `>heat.heated` means "made by anything heated, any machine".

| Property | Values | From |
|---|---|---|
| `fluid.*` | `in`, `out` | **every mod** |
| `chance.*` | `certain`, `random` | **every mod** |
| `heat.*` | `none`, `heated`, `superheated` | Create |
| `speed.*` | `fast`, `normal`, `slow` | Create, MI |
| `eu.*` | `lv`, `mv`, `hv`, `ev`, `superconductor` | Modern Industrialization |
| `shapeless` | — | any crafting recipe |
| `packing` | — | nugget↔ingot↔block round trips |

`fluid.*` and `chance.*` are free on EMI for every mod, because they fall out of the ingredient stacks
themselves. On the JEI version they only existed for Create and MI.

`chance.certain` is the quiet hero for automation. It means every output is guaranteed, so you can
build a ratio around it:

```
>crushing/chance.certain
```

versus `>crushing/chance.random`, which is where your throughput math goes to die.

---

## Lesson 4 — which machine do I need?

You know the process, not the block:

```
*mixing
```

→ Mechanical Mixer and Basin. That is it, just the machines.

```
*fan_washing      →  Encased Fan, Water
*assembler        →  MI Assembler
```

Useful when a pack has three mods that all "crush" things — here Create, Modern Industrialization and
TechReborn all do — and you want to see which blocks are actually involved before committing to one.

---

## Lesson 5 — clearing the noise

This is the other half of the original problem. Item classes are `~` tokens, each meant to be used
**negated**:

| Token | What it removes |
|---|---|
| `~decorative` | Chipped, Chipped Express, Handcrafted, Decorative Blocks, Supplementaries, Twigs, Convenient Decor, Fast Paintings, DarkPaintings, Better Beds, Immersive Lanterns, Connectible Chains |
| `~trim` | AllTheTrims, DynamicTrim, More Armor Trims, BetterTrims — one smithing recipe per material per pattern |

```
>crafting -~decorative -~trim
```

The leading `-` is EMI's own NOT operator, so this composes with everything.

Note `~` classifies the **item**, not the recipe — it works off the item's mod, which is why it
catches the thousands of furniture blocks that have no interesting recipe at all.

Add your own in `config/processsearch.json` under `decorativeModIds`.

---

## Lesson 6 — filtering recipe pages

Filtering the item grid never solved the whole problem. Open the uses of a Mechanical Mixer and you
still get two thousand pages.

**Whatever is in the search box now filters those pages too.**

1. Type `>mixing/heat.heated` in EMI's search box
2. Click an ingot, view its recipes
3. Only the heated mixing pages remain

A grey `filtered: 12 of 2043` line appears on the recipe screen, so pages never vanish mysteriously.

Three separate jobs:

- Only `>`, `<` and `*` apply here. On a recipe page a recipe either has a facet or it does not, so
  `>` and `<` filter identically. `~` describes items, so it is ignored.
- If a filter would empty every category, you get the **full** list instead. Better than a recipe
  screen that refuses to open.
- Asking for one specific recipe is never filtered.

Turn it off with `enableRecipePageFilter` in the config.

---

## Lesson 7 — the process tree

The prefixes answer one step at a time. When you want the *chain*, hover an item and press a key:

```
<   what can this be processed INTO?     (follows outputs forward)
>   what are all the ways to MAKE this?  (follows inputs backward)
```

Careful: this is the mirror of the search prefixes, where `>` means "made by". On the tree the arrow
points the way the chain runs.

Hover cobblestone, press `<`. You get **cobblestone, alone** — because machines are opt-in.

Press **Filters**. Every machine that touches cobblestone is listed, busiest first, with how many
recipes each contributed. Tick Stonecutting. The graph rebuilds: cobblestone at the top, Stonecutting
below it, and what it makes below that. Nothing else.

That is deliberate. In a 445-mod pack the alternative — everything except what you thought to
exclude — is a wall of boxes you have to dismantle before you can read anything.

### Following a chain

Click one of those items. It becomes the **focus**: the graph re-centres on it, walks one more step,
and a breadcrumb at the top shows the way back. Click the breadcrumb to jump back to any earlier
step; `Backspace` goes up one.

You are never shown the whole graph, only where you are and one step out. Everything left out is a
`+N` chip — click it and it opens. That is the only way a tree this size stays readable, and it means
depth is unlimited: you pay for where you stand, not for everything you might reach.

| | |
|---|---|
| click an item | follow it |
| click a machine | list its recipes |
| click a `+N` chip | open what it stands for |
| click an item on the bottom row | start a fresh tree there |
| click a breadcrumb | jump back to that step |
| right-click an item | start a fresh graph from there |
| drag / scroll | pan / zoom |
| `Backspace` / Back | up one step |
| `F` / Fit | frame what is on screen |
| `Home` / Root | back to the start |
| Filters | pick which machines to follow |
| Esc | back to the game |

The same screen draws routes and plans, so these all work there too — except Filters, which only a
tree has, and the direction button, which only a tree shows.

### Machines that do the same job

Grinding an ore is offered three or four ways in a big pack — a Macerator, a set of Crushing Wheels,
somebody's Pulveriser. Drawn as separate boxes that reads as several decisions when it is one.

Machines that take the same things in and give the same thing out are drawn as **one block**, three
across and three down, biggest machine first — where "biggest" means how many recipes that machine's
category holds, because a category with a thousand recipes is how the pack generally does this and a
two-recipe category is usually a one-off. Past nine, the rest become a `+N` chip that opens the list.

Only the first box in a block has anything hanging below it. The others lead to exactly the same
items by definition, so drawing their branches would be the same picture three times.

### Zooming out

Past 55% the tree switches to **compact**: labels go, icons grow, and the boxes shrink to squares so
the whole tree narrows by about four times. Plain shrinking would only turn a wide tree into a small
wide tree. Hover anything to get its name back, and scroll in to return.

Keep scrolling out and the icons keep growing to meet you — 30px of box at the threshold, 156 at the
8% floor — so they shrink on screen much slower than the tree around them. At the floor you are
looking at a very wide graph whose icons are still recognisable, packed almost edge to edge, because
the spacing stops growing once the icons get big.

The tree opens a little closer than "everything fits", because everything fitting on a graph this
wide is a grey smear. `F` re-frames it the same way.

### When a chip is hiding what you wanted

Every `+N` chip goes somewhere. The ones under a machine open the **siblings page**: a plain
scrolling list of everything that machine makes, with **no caps and no filters at all** — including
what your search excluded and what the layer budget cut. When you are sure a recipe exists and the
graph will not show it, that is the page to open.

Click any row and you get a fresh tree rooted on that item, with Filters already open.

The recipe list opens as a panel **over** the graph, which stays dimmed behind it, so Back drops you
straight back where you were. Clicking outside the panel dismisses it too.

### The search box still applies

This is what makes it usable rather than a wall of boxes:

```
>crushing              only follow crushing steps
@create                only keep branches that reach a Create item
-~decorative           never show a furniture-mod block at all
>crushing @create      combine freely
```

Three different jobs:

- **Facet tokens** (`>`, `<`, `*`) choose which recipes are followed.
- **Negated terms** (`-`) remove items outright — never drawn, never expanded.
- **Positive terms** tint what matches and pull those branches to the front when a layer has to
  choose what to draw. They never remove anything.

If you want something gone, negate it. A positive term is a preference, not a filter — that way
searching `cobblestone` leans the graph towards cobblestone without deleting the crusher for
producing gravel.

### It already skips the pointless steps

Anvil repairing, grindstone and enchanting hand you back the same kind of item you put in, so a tool
loops on itself forever. Those are dropped automatically — not by name, but because their outputs are
things they also consume. Anything else shaped like that goes too, so you will not have to find and
untick them.

### It is capped, on purpose

Two different caps, and they fail differently.

**What is drawn**: seven rows (`treeViewLayers`) — focus, then machines and items three times over.
Twelve machine *blocks* around the focus (`treeVisibleMachines` counts blocks, not boxes, so four
interchangeable grinders cost one) and six items under each; layers past that fan out by two and
share `treeVisiblePerLayer` (72). Past those you get a `+N` chip, and the
chip opens.

**What is walked**: 32 machines per item, 32 items per machine, 6000 nodes for the session. Raise
`treeMaxProcessesPerItem` / `treeMaxItemsPerProcess` if a `+N` chip is hiding something real — the
sliders for all of these are in **Mods → Process Search → config**, and they take effect on close.

An item the walk has already met links back to the node it made rather than branching again, which
is what stops cobblestone → stone → cobblestone running forever.

When that item ends up **drawn in more than one place**, every copy is outlined in amber and marked
`×2`, and hovering one lights up the others. That is worth looking for: an intermediate several
branches arrive at is the one worth automating first.

---

## Lesson 8 — routes: how do I get from A to B?

The tree walks outward one step at a time. When you already know both ends, ask for the path.

Hover something you have — copper, say — and press **shift+R**. A message confirms the anchor. Now go
and find what you want, hover it, and press **shift+R** again.

```
shift+R on copper       overlay: "Route from Copper Ingot — now press shift+r on what you want"
shift+R on the target   the route opens
```

Pressing it twice on the same item cancels.

What opens is the same graph screen, reading top to bottom: what you have, each machine in turn, what
you wanted. It searches from **both ends at once**, which is why it can afford to look eight steps
deep in a pack this size — two searches of four beat one of eight by a very long way.

Routes are shortest-by-step-count, which reads as *fewest machines*. The mod is client-side and
cannot know what you have unlocked, so it will not pretend to rank by difficulty.

### The Filters list means something different here

For the tree, Filters is a gate: nothing is followed until you tick it. For a route it is a
**preference** — equally short routes go to the machines you ticked, but a route may use anything.

That is deliberate. A tree fans out exponentially and needs an allowlist to stay finite; a route is a
targeted question already bounded by its budget, and defaulting a fresh install to "no machines" would
mean it never found anything. Set `routeRespectCategoryFilter` to make it a hard constraint — *"route
using only what I have actually built"* — which is the version you want once a pack is underway.

### When it does not find one

Four things can stop it, and they need different answers, so it says which:

| It says | It means |
|---|---|
| *no path* | nothing connects them |
| *too far* | a route exists but is longer than `routeMaxSteps` |
| *search too wide* | it ran out of nodes |
| *took too long* | it ran out of clock |

The last three put a **Deeper** button on the screen. Press it and the search runs again with all
three budgets raised; press it until it tells you it has hit the ceiling. Nobody should have to go
and edit a config file to answer a question they just asked.

---

## Lesson 9 — plans: what do I need to make this?

A route is one thread. It reads *copper, then mixing, then pressing* — but the mixing step also wants
zinc, and the pressing step wants a press you have not built.

Hover anything and press **shift+P**.

You get the whole thing: every prerequisite, resolved down to what nothing makes — ores, mob drops,
worldgen — plus the machines to build and materials to gather.

Plans stop at raw resources and never at your inventory. That is on purpose: a plan that also stopped
at whatever you happened to be carrying would change every time you picked something up, and a plan
you cannot work from twice is not a plan.

### The one thing to get right: all, not any

On the tree, the items under a machine are **alternatives** — any of these will do.

In a plan they are **requirements** — you need all of them.

Same picture, opposite meaning, so a plan says so: its machines are a different colour, labelled
`all 3`, and their tooltip spells out how many of the items above are needed.

Machines under an *item* still read as alternatives, because that part has not changed. An item is a
choice between the recipes that make it; a recipe is a demand for all of its inputs.

### Press Plan

The tree is the reasoning. The **Plan** button is the answer: the distinct machines to build, the raw
materials to gather, and anything it could not work out. That is the list you would actually work
from, and clicking a material plans *it* in turn.

Watch for `×2` marks. An item needed in several branches is the one to automate first — that is the
single most useful thing on the screen.

### When it stops short

Unlike a route, a plan usually fails *partly* — most of the tree resolved and one branch did not — so
it shows what it got and marks where it stopped rather than throwing the lot away. It has the same
**Deeper** button, and the same ceiling.

---

## Combining with EMI's own prefixes

These are parsed inside EMI's own query compiler, so they mix freely with the ones you already use:

```
$c:ingots >mixing/heat.heated      tag AND heated mixing
@create >crushing/chance.certain   Create-only, no RNG outputs
>mixing|>crushing                  either machine  (| is OR)
>mixing & >fluid.in                explicit AND, same as a space
```

EMI holds `@` mod, `#` **tooltip**, `$` **tag**, and the operators `-` NOT, `|` OR, `&` AND. This mod
adds `>` `<` `*` `~`.

> Coming from JEI? EMI's `#` and `$` are swapped relative to JEI's, and EMI has no `%creativetab`,
> `^colour` or `&id`.

**Quoting does not work after our prefixes.** `>"mechanical press"` is read as two tokens, because
EMI's tokenizer only honours quotes at the start of a token. You never need it: tokens are sanitised
to underscores, so it is `>mechanical_press`.

---

## Worked examples

**"What ingots can a heated mixer make?"** — the original question

```
$c:ingots >mixing/heat.heated
```

**"I have a pile of raw ore. What processes it?"**

```
<crushing|<milling|<macerator
```

**"Show me MI recipes I can actually power right now"** — early game, LV only

```
>modern_industrialization >eu.lv
```

**"What does the Encased Fan make when I wash things?"**

```
>fan_washing
```

**"Clean view of what crafting can make"** — no furniture, no trims, no round trips

```
>crafting -~decorative -~trim -packing
```

**"What needs a superheated basin?"**

```
>heat.superheated
```

**"I have copper. How do I get to a Precision Mechanism?"**

Hover copper, `shift+R`. Hover the mechanism, `shift+R`.

**"What do I actually need to build before I can make one?"**

Hover it, `shift+P`, then press **Plan**. The machine list is what you build; the materials list is
what you go and mine.

**"Which mods in this pack have no facets worth searching?"**

```
/processsearch gaps
```

---

## Gotchas

**Exclusion is set subtraction over items.** If an item is made by *both* a trim recipe and an
ordinary one, `-~trim` still removes it. There is no way around this — it is how the filter works.

**Items are keyed by item type, not stack.** Enchanted books, potions and other NBT variants collapse
into one entry. Right trade for automation questions, wrong if you wanted a specific NBT variant.

**Substring matching everywhere.** `>ing` matches `mixing`. Usually helpful, occasionally surprising.
Use the full compound token when you want precision.

**A search typed before the index is ready returns nothing for a moment.** EMI runs searches on its
own thread, so the build cannot block it; the query flags itself, the build starts, and the search
re-runs on its own when it finishes.

**Only Create and Modern Industrialization have hand-written facets.** Every other mod still gets
category, machine-name, title, `fluid.*` and `chance.*` tokens — so `>alloy_forgery`, `>assembler`,
`>compressor` all work — they just have no mod-specific properties like `heat.*` out of the box.

You can add them without touching code: drop a JSON file in `config/processsearch/facet_rules/` and
run `/processsearch rebuild`. `/processsearch gaps` ranks the categories that earned nothing but
their own name, biggest first, which is the list worth writing rules for. The README has the field
reference.

---

## Troubleshooting

**`stats` says `search hook: MISSING`.**
The search mixin did not apply, and the prefixes will match nothing. The mixins fail soft on purpose
so an EMI update cannot brick your world, which is exactly why this is reported out loud. Check
`logs/latest.log` for the line naming `EmiSearch$CompiledQuery.addQuery`. This build targets EMI
1.1.24.

**A prefix silently does nothing, and `stats` shows a different character than you set.**
EMI already claims `@ # $` and uses `- | &` as operators. A prefix set to one of those is refused and
falls back to the default rather than being stolen; the log says which.

**A `>` search returns nothing.**
Check `/processsearch stats` shows `index: ready`. If it says `not built`, open EMI once. Then confirm
the token exists with `/processsearch facets <partial name>` — the token is very often not the word
you assumed.

**Recipe pages did not filter.**
The filter only engages when the search box contains at least one `>`, `<` or `*` token in *every*
`|` alternative. A query that is partly plain text deliberately leaves recipe pages alone.

**Index build feels slow.**
It is capped at 3 ms/tick. `stats` reports work time and elapsed time separately — a big gap between
them means the cap is working, not that something is wrong. Raise `buildTimeBudgetMillisPerTick`, or
add slow categories to `excludedCategories`. In this pack the EMI Loot, EMI Ores and EMI Trades
categories are the obvious candidates.

**Results look like the last world's.**
The index is kept when you leave a world and reused on the next join if the recipes fingerprint the
same, so hopping singleplayer → menu → server does not rebuild it. The one thing the check cannot
see is a server that changes a recipe's *inputs* under an unchanged id, keeping the same input count
and outputs. `/processsearch rebuild` fixes it on the spot; `reuseIndexAcrossWorlds: false` in the
config turns reuse off entirely.

**`<` and `>` do nothing over an item.**
Check `/processsearch stats`. It prints the two keys it is listening for and whether the key hook has
fired — press any key with EMI open, then run it again. On a non-US keyboard `<` and `>` are not
shift+comma and shift+period; set `treeConsumersKey` and `treeProducersKey` to whatever they are.
Note the keys deliberately do nothing while the search box has focus, because there they type.

**The tree looks almost empty.**
That is usually the search box: negated terms remove items outright, and positive ones keep only
branches that reach a match. Greyed nodes are the ones a positive term cut — click to expand them
anyway. Failing that, the caps are doing their job; narrow the search rather than raising them.

**The header says `index not ready — filters skipped`.**
The query used `~` or a facet prefix, which only the process index can answer, and it was not built
yet. The tree deliberately refuses to half-apply a filter there — with no index a negated `~` term
would silently admit everything. Open EMI for a moment, then reopen the tree.

**A route or plan hotkey does nothing.**
Same check as `<` and `>`: `/processsearch stats` prints all four keys and whether the key hook has
fired. `shift+R` and `shift+P` are `treeRouteKey` and `treePlanKey` in the config.

**A plan bottoms out somewhere odd, or picks a strange recipe.**
It takes the first recipe that fully resolves, and the order is: machines you ticked in Filters
first, then the busiest category, then fewest ingredients. Ticking the machines you actually use is
the lever. A branch it could not work out is listed under *Could not work out* in the Plan panel
rather than hidden.

**Something that used to work stopped after an EMI update.**

```
/processsearch compat
```

This mod hooks EMI internals, not published API, and the mixins fail soft so an EMI update cannot
brick your world — the cost being that a moved target is silent. `compat` checks every one of them
and prints `ok`, `unproven` or `missing`. *Unproven* usually just means you have not used that
feature yet this session; use it and re-run. *Missing* names what broke and what stopped working.

**Start over:** `/processsearch rebuild` — also reloads the config file.

---

## Which packs does this work in?

**Requires: Minecraft 1.20.1 · Fabric · EMI 1.1.x**

Built for and tested against **Prominence II: Hasturian Era v4.0.2** (445 mods, EMI 1.1.24, Create
fabric 6.0.8.1, Modern Industrialization 1.8.6).

Any 1.20.1 Fabric pack with EMI should work. Without Create or MI you lose the hand-written
`heat.*`, `eu.*` and `speed.*` facets, but categories, machine names, item classes, `fluid.*` and
`chance.*` all still function — and the tree, routes and plans do not depend on any of them, because
they read EMI's recipe graph directly.

For anything else you want searchable, write a facet rule: `config/processsearch/facet_rules/`, no
code and no rebuild of the mod. `/processsearch gaps` tells you where it would be worth doing.

EMI is pinned to `1.1.x`. A 1.2 would refuse to load the mod rather than half-working, which is the
safer failure for something that reaches this far into EMI's internals.

For **Minecraft 1.21.1 / NeoForge / JEI**, use the sibling project in `JEI-ProcessSearch` instead.
