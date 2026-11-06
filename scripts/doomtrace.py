#!/usr/bin/env python3
"""Following groups through their choices: the two passes of the doom trace (see docs/tracing-groups.md).

  doomtrace.py pick OUT [--died N] [--hungry N] [--control N]
      From a pass-1 run's output (stdout+stderr, with doomList=true), the doomTrace= key for pass 2: the bands that died
      out after hunger deaths, the living ones with the most hunger deaths in a year, and the control bands listed.
  doomtrace.py summary OUT [OUT ...] [--bands]
      From pass-2 outputs (with doomTrace=...), each followed band's weeks put into cases, doomed against control:
      better ground within reach (stayed / moved), better only beyond reach, nothing better; reach holding only the
      band's own hex; hungry weeks (fed < 0.9) apart. A file may hold several runs, each headed by a line '=== NAME ...'.
  doomtrace.py lean OUT [OUT ...]
      From outputs with doomList=true, the lean table summed over all years and runs: by kind of country, its share of
      band-weeks, the share of them drawing on fat and fed under 0.9, moving camp, and hunger deaths per 1000 people a
      year; then by country and month, hunger deaths per 1000 people a year and the share drawing on fat.
"""
import collections, re, sys

LIST = re.compile(r'doomList (control )?#(\d+) (died out|year to) [^:]*: (\d+|no) hunger deaths')
TRACE = re.compile(r'doom #(?P<id>\d+) Year (?P<y>\d+), month (?P<m>\d+), day (?P<d>\d+): (?P<people>\d+) people, fed (?P<fed>[\d.]+), '
    r'fat (?P<fat>[\d.]+) d, larder (?P<larder>[\d.]+) d, yield (?P<yld>\S+?)(?P<restless>, restless)?; reach (?P<reach>\d+) \((?P<hexes>\d+) hexes\): '
    r'here (?P<here>-?\d+), chose (?P<chose>-?\d+)(?P<stay> \(stays\)| at (?P<km>\d+))(?: \(crowd (?P<crowd>[\d.]+), by (?P<by>\w+), mind (?P<mind>\w+)\))?; '
    r'best in reach (?P<inbest>-?\d+) at (?P<inkm>\d+) \((?P<better>\d+) places [\d.]+x\); within (?P<wide>\d+): best (?P<wbest>-?\d+) at (?P<wkm>\d+) '
    r'\(crowd (?P<wcrowd>[\d.]+)\), first [\d.]+x at (?P<first>\S+)')

def runs(path):
    """(run name, line) for each line of [path]; the run is the last '=== NAME' heading, else the file's name."""
    name = path
    for l in open(path, errors='replace'):
        if l.startswith('=== '): name = l.split()[1]; continue
        yield name, l

def listed(path):
    """Per run: band id -> 'died' / 'hungry' / 'control', with its most hunger deaths in a year."""
    out = collections.defaultdict(dict)
    for run, l in runs(path):
        m = LIST.search(l)
        if not m: continue
        ctl, i, how, n = m.groups()
        kind = 'control' if ctl else ('died' if how == 'died out' else 'hungry')
        deaths = 0 if n == 'no' else int(n)
        old = out[run].get(i)
        rank = {'died': 2, 'hungry': 1, 'control': 0}
        if old is None or rank[kind] > rank[old[0]] or (kind == old[0] and deaths > old[1]): out[run][i] = (kind, deaths)
    return out

def pick(args):
    path = args[0]
    opt = dict(zip(args[1::2], args[2::2]))
    nd, nh, nc = int(opt.get('--died', 8)), int(opt.get('--hungry', 4)), int(opt.get('--control', 8))
    for run, bands in listed(path).items():
        died = sorted((b for b, (k, _) in bands.items() if k == 'died'), key=lambda b: -bands[b][1])[:nd]
        hungry = sorted((b for b, (k, _) in bands.items() if k == 'hungry'), key=lambda b: -bands[b][1])[:nh]
        # (A band once listed hungry or dead is no control, whatever another year said.)
        ctl = sorted(b for b, (k, _) in bands.items() if k == 'control')[:nc]
        print(f"{run}: doomTrace={','.join(died + hungry + ctl)}   # died {len(died)}, hungry {len(hungry)}, control {len(ctl)}")

CASES = ['better in reach, stayed', 'better in reach, moved', 'better only beyond reach', 'nothing better']

def case(w):
    if int(w['better']) > 0: return CASES[0] if w['stay'] == ' (stays)' else CASES[1]
    return CASES[2] if w['first'] != 'none' else CASES[3]

def summary(args):
    show = '--bands' in args
    paths = [a for a in args if not a.startswith('--')]
    tables = collections.defaultdict(collections.Counter)
    for path in paths:
        kinds = listed(path)
        weeks = collections.defaultdict(list)
        for run, l in runs(path):
            m = TRACE.match(l)
            if m: weeks[(run, m['id'])].append(m.groupdict())
        for (run, i), ws in weeks.items():
            group = 'control' if kinds.get(run, {}).get(i, ('?',))[0] == 'control' else 'doomed'
            arm = run.split('-')[0]
            c = collections.Counter()
            for w in ws:
                hungry = float(w['fed']) < 0.9
                for part in (['all'] + (['hungry'] if hungry else [])):
                    c[(part, 'weeks')] += 1
                    c[(part, case(w))] += 1
                    if w['restless'] is None: c[(part, 'not restless')] += 1
                    if int(w['hexes']) == 1: c[(part, 'own hex only in reach')] += 1
            tables[(arm, group)].update(c)
            if show:
                print(f"{run} #{i} ({group}): {len(ws)} weeks, people {ws[0]['people']} -> {ws[-1]['people']}; " +
                    ", ".join(f"{k} {c[('all', k)]}" for k in CASES + ['not restless', 'own hex only in reach']))
    for (arm, group), c in sorted(tables.items()):
        for part in ('all', 'hungry'):
            n = c[(part, 'weeks')]
            if n == 0: continue
            print(f"{arm} {group}, {part} weeks ({n}): " + ", ".join(f"{k} {100 * c[(part, k)] / n:.0f}%"
                for k in CASES + ['not restless', 'own hex only in reach']))

LEAN = re.compile(r'doomLean year to [^:]*: (?P<country>[^,]+), month (?P<m>\d+): (?P<counts>.*)')
PREY = re.compile(r'doomPrey year to [^:]*: (?P<country>[^:]+): fat reserves % of full by month: (?P<ms>.*)')

def lean(args):
    """The lean table (doomLean lines) summed over the files given, by kind of country, then by country and month."""
    rows = collections.defaultdict(collections.Counter)
    prey = collections.defaultdict(list)
    for path in args:
        for _, l in runs(path):
            q = PREY.search(l)
            if q: prey[q['country']].append([None if v == '-' else float(v) for v in q['ms'].split()])
            m = LEAN.search(l)
            if not m: continue
            for part in m['counts'].split(', '):
                k, v = part.rsplit(' ', 1)
                rows[(m['country'], int(m['m']))][k] += int(v)
    by = collections.defaultdict(collections.Counter)
    for (c, _), r in rows.items(): by[c].update(r)
    allw = sum(r['band-weeks'] for r in by.values()) or 1
    # (Hunger deaths a year per 1000 people: people-weeks to years by the weeks a year the run steps, 60 at steps of 6 days of a 360-day year.)
    def per(r): return 1000 * r['hunger deaths'] / max(1, r['people-weeks']) * 60
    def mean(r, k, scale, n='band-weeks'): return r[k] / scale / max(1, r[n]) if k in r else float('nan')
    print('by kind of country: share of band-weeks; fat drawn, fed<0.9, moved, % of its band-weeks; hunger deaths a year per 1000;'
          ' means: fat, larder (at most 120), of it what keeps (fresh meat and fruit left out), store aim, days of need; 4-week yield; share of days spare')
    for c, r in sorted(by.items(), key=lambda x: -x[1]['band-weeks']):
        w = max(1, r['band-weeks'])
        print(f"  {c:24} {100*r['band-weeks']/allw:5.1f}%  fat {100*r['fat drawn']/w:5.1f}%  fed<0.9 {100*r['fed<0.9']/w:5.1f}%  moved {100*r['moved']/w:5.1f}%  hunger {per(r):6.1f}"
              f"  | fat {mean(r, 'fat d/10', 10):5.1f}  larder {mean(r, 'larder d/10', 10):5.1f}  durable {mean(r, 'durable d/10', 10):5.1f}  aim {mean(r, 'store aim d/10', 10):5.1f}"
              f"  yield {mean(r, 'yield/100', 100, 'yield weeks'):4.2f}  spare {mean(r, 'spare/100', 100):4.2f}")
    print('by country and month (months 1-12): hunger deaths a year per 1000 | fat drawn % | larder d | yield')
    for c in sorted(by, key=lambda c: -by[c]['band-weeks']):
        if by[c]['band-weeks'] < 0.01 * allw: continue
        ms = [rows.get((c, m), collections.Counter()) for m in range(1, 13)]
        print(f"  {c:24} " + ' '.join(f"{per(r):4.0f}" for r in ms) + ' | ' + ' '.join(f"{100*r['fat drawn']/max(1, r['band-weeks']):3.0f}" for r in ms)
              + ' | ' + ' '.join(f"{mean(r, 'larder d/10', 10):4.0f}" for r in ms) + ' | ' + ' '.join(f"{mean(r, 'yield/100', 100, 'yield weeks'):4.2f}" for r in ms))
    if prey:
        print('game fat reserves by country and month (months 1-12), % of full, biomass-weighted, mean of the year-lines given:')
        for c in sorted(prey, key=lambda c: -by[c]['band-weeks'] if c in by else 0):
            cols = list(zip(*prey[c]))
            print(f"  {c:24} " + ' '.join(f"{sum(x for x in col if x is not None) / max(1, sum(1 for x in col if x is not None)):4.0f}" for col in cols))

PERIL = re.compile(r'peril #(?P<id>\d+) (?P<date>[^:]+): (?P<people>\d+) people, mind (?P<mind>\w+), by (?P<by>\w+)(?P<restless>, restless)?(?P<party>, party out)?; '
    r'camp peril (?P<camp>[\d.]+) \((?P<stay>[^)]*)\); reach (?P<reach>\d+) km \((?P<hexes>\d+) hexes, (?P<free>\d+) free, nearest free at (?P<near>\S+)\); '
    r'chose peril (?P<chose>[\d.]+)(?P<where> \(stays\)| at \d+), (?P<cho>[^;]*); weighed (?P<n>\d+), safest steps (?P<safest>[^;]+); '
    r'free weighed (?P<fw>\d+) \(not open (?P<shut>\d+), no score (?P<unsc>\d+)\), best open free: (?P<bf>.*?)(?:; way out (?P<out>.*))?$')
PERIL_END = re.compile(r'perilEnd #(?P<id>\d+) (?P<date>[^:]+): (?P<end>.*)')
DAY = re.compile(r'Year (\d+), month (\d+), day (\d+)')
PERIL_CASES = ['no free place in reach', 'went to a free place', 'free in reach, none weighed', 'free weighed, none open', 'open free place, chose a perilous one']

def peril_case(w):
    if float(w['chose']) == 0: return PERIL_CASES[1]
    if int(w['free']) == 0: return PERIL_CASES[0]
    if int(w['fw']) == 0: return PERIL_CASES[2]
    if w['bf'].strip() == 'none': return PERIL_CASES[3]
    return PERIL_CASES[4]

def day(date):
    m = DAY.search(date)
    return (int(m[1]) * 12 + int(m[2])) * 30 + int(m[3]) if m else None

def peril(args):
    show = '--bands' in args
    warned = collections.Counter(); why = collections.Counter(); killed = collections.Counter(); stretched = collections.Counter(); progress = collections.Counter(); struck = 0
    shown = []
    for path in (a for a in args if not a.startswith('--')):
        last = {}; now = {}; stretch = collections.defaultdict(list)
        for run, l in runs(path):
            m = PERIL.match(l)
            if m:
                w = m.groupdict(); w['run'] = run
                key = (run, w['id'])
                if float(w['camp']) < 1:
                    c = peril_case(w); warned[c] += 1
                    if c == PERIL_CASES[4]: why[(w['mind'], w['by'], 'stays' if 'stays' in w['where'] else 'moves')] += 1
                    if show and c in PERIL_CASES[2:]: shown.append(l.rstrip())
                else: struck += 1
                now[key] = (w, last.get(key)); last[key] = w
                st = stretch[key]; d = day(w['date'])
                if st and (d is None or st[-1][0] is None or d - st[-1][0] > 8): st.clear()
                st.append((d, w))
                continue
            m = PERIL_END.match(l)
            if m and m['end'] == 'died out':
                key = (run, m['id'])
                w, before = now.get(key, (None, None))
                if w is None or float(w['camp']) < 1: continue
                d0, d1 = day(before['date']) if before else None, day(w['date'])
                ws = [x for _, x in stretch[key][:-1]]
                if not ws: c = 'not warned'
                elif any(float(x['chose']) == 0 for x in ws): c = 'reached a free place, then the peril came onto it'
                elif any(peril_case(x) == PERIL_CASES[3] for x in ws): c = 'a free place in reach some week, none open'
                elif any(peril_case(x) == PERIL_CASES[4] for x in ws): c = 'an open free place some week, chose a perilous one'
                else: c = 'never a free place in reach'
                stretched[c] += 1
                outs = [x.get('out') or '' for x in ws]
                if ws and all('no progress' in o for o in outs): progress['a way out every week, never closer in reach'] += 1
                elif ws and any('no progress' in o for o in outs): progress['a way out, some weeks no closer in reach'] += 1
                elif ws and all(o.startswith('none') for o in outs if o): progress['no way out found'] += 1
                if before is None or d0 is None or d1 is None or d1 - d0 > 8: killed['not warned the week before'] += 1
                else:
                    c = peril_case(before); killed[c] += 1
                    if show: shown.append('killed; the week before: ' + ' '.join(f'{k}={before[k]}' for k in ('run', 'id', 'date', 'camp', 'free', 'near', 'chose', 'where', 'cho', 'safest', 'fw', 'shut', 'unsc', 'bf')))
    total = sum(warned.values()) or 1
    print(f'band-weeks warned of a peril at camp: {sum(warned.values())}; with the camp struck: {struck}')
    for c in PERIL_CASES: print(f'  {c:40} {warned[c]:6d}  {100*warned[c]/total:5.1f}%')
    if why: print('  of "chose a perilous one", by mind, net or hand, stays or moves: ' + ', '.join(f'{k[0]}/{k[1]}/{k[2]} {v}' for k, v in why.most_common()))
    print(f'bands that died with their camp struck: {sum(killed.values())}, by the case of their week before:')
    for c in ['not warned the week before'] + PERIL_CASES:
        if killed[c]: print(f'  {c:40} {killed[c]:6d}')
    print('the same bands by their whole warned stretch (consecutive warned weeks before the strike):')
    for c, v in stretched.most_common(): print(f'  {c:52} {v:6d}')
    if progress:
        print('and by the way out ([searchWayOut]; traces from #684 on):')
        for c, v in progress.most_common(): print(f'  {c:52} {v:6d}')
    for l in shown: print(l)

if __name__ == '__main__':
    if len(sys.argv) < 3 or sys.argv[1] not in ('pick', 'summary', 'lean', 'peril'): sys.exit(__doc__)
    {'pick': pick, 'summary': summary, 'lean': lean, 'peril': peril}[sys.argv[1]](sys.argv[2:])
