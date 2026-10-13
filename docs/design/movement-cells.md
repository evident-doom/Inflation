# Movement cells

The simulation is meant to run for centuries, one week per step, so a step must not get
dearer. But groups that move tens of km in a step along fine routes can pass each other, share a
pass or walk through an ambush without the model ever knowing they met. The fix is to make the
place a group stands on as large as a week's move: then a week's step is at most one cell, and
"same cell" is the meeting check.

## Cells and links

- Groups stand on hexagonal cells about 10 km across. All six neighbours are equally far, so a
  step is one cell in any direction, and nothing travels faster than about 10 km a week.
- The 1 km terrain stays the source of truth. Once per world, each pair of neighbouring cells is
  linked by the easiest 1 km route between them, with its effort. A pass is a dear link across a
  ridge; a ford is a link across a river that floods shut in spate; a gorge is a dear link; where
  there is no way through, there is no link. Climbers (ibex, mouflon, leopards) have their own
  links where the ground is too steep for others.
- Each cell carries summaries of its 1 km ground, refreshed as the world changes: forage of each
  kind, cover, water, snow, its highest and lowest ground and how much lies at each height, so a
  group in a mountain cell can graze the high pasture in summer and suffers the snow in winter.

## Who stands where

- Migratory herds, hunters and bands are groups on cells. Each week a group either stays or steps
  to a linked neighbour. The choice uses the same features as now, worked out per cell.
- Resident plant-eaters become densities per cell (the fields): they grow, starve, are hunted and
  spill into neighbouring cells, but do not wander.
- Groups in the same cell in the same week can meet: hunt, fight, trade, talk, raid. A group that
  holds a cell (a camp, a sentry on a pass, an ambush) meets everyone who enters it. A group that
  steps from A to B while another steps from B to A meets it on the link.

## What it costs

- The weekly step gets cheaper, not dearer: candidate moves are seven per group, not twenty-five,
  and encounters are a lookup by cell.
- Travel is slow: an altitudinal shift takes a week or two, and a 200 km trek takes about a season.
  That suits herds and bands; fast raids across the map are ruled out.
- Everything inside a cell (which valley, which bank) is lost for meeting, kept only as cell
  summaries.

## Order of work

1. Hex grid, cell summaries and links from the 1 km terrain; a report of how the links follow
   passes and fords.
2. Groups on cells: positions, one-step moves, the planning features per cell.
3. Resident plant-eaters as per-cell densities.
4. Encounters by cell: hunting, rivals, bands meeting, raids.
5. Viewer: cells, links, groups stepping cell to cell.
6. Retrain herds, hunters and people on the new world.
