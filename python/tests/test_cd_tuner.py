import copy
import math
import random
from types import SimpleNamespace

import pytest

from sdfx import cd_tuner as cdt
from sdfx.protocol import EngineError

torch = pytest.importorskip("torch")

# ---- literal restatements of the pinned source (scripts/cdtuner.py aafdbdd), A1111 backend, no refiner ----------
SRC_COLS = [[-1, 1 / 3, 2 / 3], [1, 1, 0], [0, -1, -1], [1, 0, 1]]
SRC_COLSXL = [[0, 0, 1], [1, 0, 0], [-1, -1, 0], [-1, 1, 0]]
SRC_DEFAULTC = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0]
# diffusers names of the source's ADJUSTS, in its order
SRC_ADJUSTS = ["conv_in.weight", "conv_in.bias", "conv_norm_out.weight", "conv_norm_out.bias", "conv_out.bias"]


def src_fineman(fine):
    return [1 - fine[0] * 0.01, 1 + fine[0] * 0.02, 1 - fine[1] * 0.01, 1 + fine[1] * 0.02, [fine[2] * 0.02, 0, 0, 0]]


def src_colorcalc(cols, isxl):
    colors = SRC_COLSXL if isxl else SRC_COLS
    outs = [[y * cols[i] * 0.02 for y in x] for i, x in enumerate(colors)]
    return [sum(x) for x in zip(*outs)]


def src_makecells(aratio, mode):
    if not ("Hor" in mode or "Ver" in mode):
        mode = "Vertical"
    aratio = aratio.replace(",", " ")
    aratios = aratio.split("|") if "|" in aratio else aratio.split(";")
    if len(aratios) == 1:
        aratios[0] = "1 " + aratios[0]
    icells, ocells = [], []

    def startend(lst):
        o, s = [], 0
        lst = [v / sum(lst) for v in lst]
        for i in range(len(lst)):
            o.append([0, lst[0]] if i == 0 else [s, s + lst[i]])
            s = s + lst[i]
        return o

    for rc in aratios:
        rc = [float(r) for r in rc.split(" ")]
        if len(rc) == 1:
            rc = [rc[0]] * 2
        ocells.append(rc[0])
        icells.append(startend(rc[1:]))
    return startend(ocells), icells


def src_colors(colors):
    colors = colors.split("|") if "|" in colors else colors.split(";")
    for i, cols in enumerate(colors):
        if " " in cols:
            colors[i] = cols.split(" ")
        if "," in cols:
            colors[i] = cols.split(",")
    out = [[float(v) for v in cols] for cols in colors]
    for cols in out:
        if len(cols) == 3:
            cols.insert(0, sum(cols) / 3)
    return out


def get_t(model, name):
    module = model
    for part in name.split(".")[:-1]:
        module = getattr(module, part)
    return getattr(module, name.split(".")[-1])


class SrcScript:
    """The source Script for one batch: process_batch (after prompt overrides) and the two callbacks."""

    def __init__(self, allsets, allsets_c, *, hr, batch, isxl, model, opts=()):
        self.model, self.isxl, self.opts = model, isxl, list(opts)
        self.active = self.activec = False
        self.storedweights = {}
        self.shape = None
        self.done = [False, False]
        self.pas = 0
        self.colored = 0
        self.colors = []
        ratios, cmode, colors, fst, att = allsets_c
        if allsets == SRC_DEFAULTC and (att == 0 or colors == ""):
            return
        if not all(x == 0 for x in allsets[:10] + allsets[14:16]):
            self.active = True
            self.drratios = allsets[0:3] + allsets[8:10]
            self.ddratios = allsets[3:8]
            self.scaling = allsets[10]
            self.sts = allsets[11:14]
            self.hr = hr
        if colors != "":
            self.activec = True
            if not ("Hor" in cmode or "Ver" in cmode):
                cmode = "Vertical"
            self.cmode, self.fst, self.att, self.batch = cmode, int(fst), float(att) * 0.5, batch
            self.ocells, self.icells = src_makecells(ratios, cmode)
            self.colors = src_colors(colors)
            self.satt = 1.15 - self.fst * 0.15 if 5 > self.fst else 0.4

    def restoremodel(self):
        with torch.no_grad():
            for name in SRC_ADJUSTS:
                if name in self.storedweights:
                    get_t(self.model, name).copy_(self.storedweights[name])

    def stopper(self, pas, step):
        judge = False
        if 0 > self.sts[pas]:
            return False
        if step >= self.sts[pas]:
            judge = True
        if judge and self.done[pas]:
            self.restoremodel()
            self.done[pas] = False
        return judge

    def denoiser_callback(self, x, step):
        if self.activec:
            if self.shape is None:
                self.shape = x.shape
            if x.shape[-2] * x.shape[-1] > self.shape[-2] * self.shape[-1]:
                self.colored = 0
                self.pas = 1
            cond_last = x.shape[0] > self.batch
            passes = 1 if cond_last else 2
            if self.colored // passes == step and self.colored // passes < self.fst:
                c = 0
                h, w = x.shape[-2], x.shape[-1]
                enhance = 6
                hr_att = 0.25 if self.pas else 1
                for i, ocell in enumerate(self.ocells):
                    for icell in self.icells[i]:
                        if "Ver" in self.cmode:
                            s3 = slice(int(h * ocell[0]), int(h * ocell[1]))
                            s4 = slice(int(w * icell[0]), int(w * icell[1]))
                        elif "Hor" in self.cmode:
                            s3 = slice(int(h * icell[0]), int(h * icell[1]))
                            s4 = slice(int(w * ocell[0]), int(w * ocell[1]))
                        colorvec = src_colorcalc(self.colors[c], self.isxl)
                        targets, offsets, weight, keep = [1, 2, 3], [0.0] + list(colorvec), 1.0, None
                        cratio = (sum(abs(v * 50) for v in colorvec)) / 10 * weight * (1 / (1 + (1 + step) ** 1.5 / 10)) \
                            * self.att * hr_att * self.satt
                        if 0 > cratio:
                            c += 1
                            continue
                        for s2 in targets:
                            scale = torch.mean(torch.abs(x[:, s2]))
                            blend = (1 - cratio) if keep is None else keep
                            shift = offsets[s2] * enhance * scale * cratio
                            x[:-self.batch, s2, ..., s3, s4] = blend * x[:-self.batch, s2, ..., s3, s4] - shift
                            x[-self.batch:, s2, ..., s3, s4] = blend * x[-self.batch:, s2, ..., s3, s4] + (shift if cond_last else -shift)
                        c += 1
                self.colored += 1
        if self.active:
            if self.shape is None:
                self.shape = x.shape
            if x.shape[2] * x.shape[3] > self.shape[2] * self.shape[3]:
                self.pas = 1
                scale = ((x.shape[2] * x.shape[3]) / (self.shape[2] * self.shape[3])) if self.scaling else 1
            else:
                scale = 1
            ratios = self.drratios
            if self.stopper(self.pas, step):
                return
            if self.done[self.pas]:
                return
            self.done[self.pas] = True
            if self.pas:
                if ratios[-2]:
                    ratios[0] = ratios[-2]
                if ratios[-1]:
                    ratios[1] = ratios[-1]
            ratios[:2] = [v * scale for v in ratios[:2]]
            ratios = src_fineman(ratios)
            for i, name in enumerate(SRC_ADJUSTS):
                if name not in self.storedweights:
                    self.storedweights[name] = get_t(self.model, name).clone()
                dtype = self.storedweights[name].dtype
                base = self.storedweights[name].to("cpu", dtype)
                new = base * torch.tensor(ratios[i]).to("cpu", dtype) if 4 > i else \
                    base + torch.as_tensor(ratios[i]).to("cpu", dtype)
                with torch.no_grad():
                    get_t(self.model, name).copy_(new)
            self.shape = x.shape

    def denoised_callback(self, x, step, total):
        if self.active:
            if self.hr and not self.pas:
                return
            if step == total - 2 - self.sts[2]:
                if any(v != 0 for v in self.ddratios):
                    ratios = [self.ddratios[0] * 0.02] + src_colorcalc(self.ddratios[1:], self.isxl)
                    for i, v in enumerate(ratios):
                        if i >= x.shape[1]:
                            break
                        x[:, i, :, :] = x[:, i, :, :] - v * 20 / 3

    def postprocess_batch(self):
        if True in self.done:
            self.restoremodel()
        self.done = [False, False]
        if "Apply once" in self.opts:
            self.active = self.activec = False


# ---- small models --------------------------------------------------------------------------------------------
class TinyUnet(torch.nn.Module):
    def __init__(self):
        super().__init__()
        gen = torch.Generator().manual_seed(5)
        self.conv_in = torch.nn.Conv2d(4, 8, 3, padding=1)
        self.conv_norm_out = torch.nn.GroupNorm(4, 8)
        self.conv_out = torch.nn.Conv2d(8, 4, 3, padding=1)
        with torch.no_grad():
            for p in self.parameters():
                p.copy_(torch.randn(p.shape, generator=gen) * 0.3 + 0.1)
        self.config = SimpleNamespace(out_channels=4, time_cond_proj_dim=None)
        self.seen = []

    def weights(self):
        return [get_t(self, n).detach().clone() for n in SRC_ADJUSTS]

    def eps(self, sample):
        return 0.3 * sample + self.conv_out.bias.view(1, 4, 1, 1) * 0.5 + self.conv_in.weight.mean()

    def forward(self, sample, timestep, encoder_hidden_states=None):
        self.seen.append((sample.detach().clone(), self.weights()))
        return SimpleNamespace(sample=self.eps(sample))


ALPHAS = torch.linspace(0.999, 0.02, 1000)


def scales(t):
    a = float(ALPHAS[t])
    return math.sqrt(a), math.sqrt(1 - a)


def timesteps(n):
    return [900 - (800 * i) // max(1, n - 1) for i in range(n)]


def config(**kw):
    c = dict(cdt.DEFAULTS)
    c.update(kw)
    return cdt.normalize(c)


def allsets_of(c):
    return [c[k] for k in cdt.IDENTIFIERS], [c["split_ratio"], c["split_by"], c["colors"], c["map_stop_step"],
                                             c["map_strength"]]


def run_both(c, passes, *, hires, batch=1, sdxl=False, prediction="epsilon", opts=()):
    """Runs `passes` [(name, size, steps)] through NUI's PassHook and the restated source; returns both traces."""
    base_unet = TinyUnet()
    nui, src = copy.deepcopy(base_unet), copy.deepcopy(base_unet)
    allsets, allsets_c = allsets_of(c)
    script = SrcScript(list(allsets), list(allsets_c), hr=hires, batch=batch, isxl=sdxl, model=src, opts=opts)
    job = cdt.Job(hires)
    sched = SimpleNamespace(alphas_cumprod=ALPHAS, config={"prediction_type": prediction})
    gen = torch.Generator().manual_seed(11)
    trace_nui, trace_src, batch_ended = [], [], False
    for name, size, steps in passes:
        if name not in cdt.MAIN_PASSES and not batch_ended:
            script.postprocess_batch()
            batch_ended = True
        if name == "base":
            batch_ended = False
        state = job.state_for(name, lambda: cdt.TunerState(c, sdxl=sdxl, hr=hires, batch=batch))
        state.batch = batch
        hook = cdt.PassHook(job, state, name, nui, None, sched, SimpleNamespace(_num_timesteps=steps), prediction,
                            steps).install()
        try:
            for step, t in enumerate(timesteps(steps)):
                lat = torch.randn(batch, 4, size, size, generator=gen)
                sa, s1a = scales(t)
                # NUI: diffusers rows [uncond, cond]; the scheduler forms x0 from the unedited input
                out = nui(torch.cat([lat, lat]), torch.tensor(t)).sample
                z = torch.cat([lat, lat])
                x0 = (z - s1a * out) / sa if prediction == "epsilon" else sa * z - s1a * out
                trace_nui.append((name, step, nui.seen[-1][1], x0[batch:], x0[:batch]))
                hook.callback(None, step, t, {})
                # source: k-diffusion rows [cond, uncond], the denoised result comes from the edited x_in
                x_in = torch.cat([lat, lat])
                script.denoiser_callback(x_in, step)
                weights = src.weights()
                eps = src.eps(x_in)
                denoised = (x_in - s1a * eps) / sa
                script.denoised_callback(denoised, step, steps)
                trace_src.append((name, step, weights, denoised[:batch], denoised[batch:]))
        finally:
            hook.remove()
        assert all(torch.equal(a, b) for a, b in zip(nui.weights(), base_unet.weights())), "weights not restored"
    return trace_nui, trace_src


def assert_same(trace_nui, trace_src):
    assert len(trace_nui) == len(trace_src)
    for (name, step, w_n, c_n, u_n), (_, _, w_s, c_s, u_s) in zip(trace_nui, trace_src):
        for a, b in zip(w_n, w_s):
            assert torch.equal(a, b), (name, step)
        assert torch.allclose(c_n, c_s, atol=2e-5), (name, step, (c_n - c_s).abs().max())
        assert torch.allclose(u_n, u_s, atol=2e-5), (name, step, (u_n - u_s).abs().max())


# ---- tests ---------------------------------------------------------------------------------------------------
def test_tables_match_source():
    for d in ([3.0, -2.0, 4.5], [0.0, 0.0, 0.0], [-10.0, 7.3, -20.0]):
        assert cdt.fine_ratios(d) == src_fineman(d)
    rng = random.Random(1)
    for _ in range(20):
        cols = [rng.uniform(-20, 20) for _ in range(4)]
        for xl in (False, True):
            assert cdt.color_offsets(cols, xl) == src_colorcalc(cols, xl)


@pytest.mark.parametrize("ratio", ["1,1", "1", "3,1,2,1", "2,1,3;3,4,6", "1,2|3,1,1", "2.5 1"])
@pytest.mark.parametrize("mode", ["Horizontal", "Vertical"])
def test_cells_match_source(ratio, mode):
    assert cdt.make_cells(ratio, mode) == src_makecells(ratio, mode)


def test_cells_and_colours_reject_what_the_source_cannot_parse():
    for bad in ("1, 1", "1,,1", "a,1", "0,0"):
        with pytest.raises(EngineError):
            cdt.make_cells(bad, "Horizontal")
    for text in ("5 0 0;0 5 5;0 0 -5;5 5 5", "5 0 0|-5 0 0", "5, -5, -5", "1 2 3 4;-5,0,5"):
        assert cdt.parse_colors(text) == src_colors(text)
    for bad in ("5", "5 0", "5 0 0 0 0", "5  0 0", "5 0 0;", "x 0 0", "5 0 0;0 0 5|-5 5 0"):
        with pytest.raises(EngineError):
            cdt.parse_colors(bad)
    with pytest.raises(EngineError, match="regions"):
        cdt.TunerState(config(colors="5 0 0", split_ratio="1,1,1"), sdxl=False, hr=False, batch=1)


def test_normalize():
    c = config(d1="2.5", map_stop_step="3", map_strength=" 0.4 ", apply_once="true")
    assert (c["d1"], c["map_stop_step"], c["map_strength"], c["apply_once"]) == (2.5, 3, 0.4, True)
    assert config(map_stop_step=2.9)["map_stop_step"] == 2
    assert (config(hrs=True)["hrs"], config(hrs=False)["hrs"], config(hrs=3)["hrs"]) == (1.0, 0.0, 3.0)
    for bad in (dict(d1="x"), dict(sat=float("nan")), dict(map_stop_step="2.5"), dict(colors=5), dict(hrs="on")):
        with pytest.raises(EngineError):
            config(**bad)


def test_detail_contrast_and_stops_follow_source_over_base_hires_and_follow_up():
    c = config(d1=3, d2=-2, con1=4, hd1=5, hrs=1, st1=4, st2=-1, con2=2, bri=-3, col1=1.5, col2=-2, col3=4)
    passes = [("base", 8, 6), ("hires", 12, 5), ("adetailer", 8, 4)]
    trace_nui, trace_src = run_both(c, passes, hires=True)
    assert_same(trace_nui, trace_src)
    weights = {(n, s): w for n, s, w, _, _ in trace_nui}
    original = TinyUnet().weights()
    assert not torch.equal(weights[("base", 0)][0], original[0])
    assert torch.equal(weights[("base", 4)][0], original[0])  # Stop Step 4 restores
    # hires: hd1 replaces d1 and hr-scaling multiplies by the area ratio (144 / 64)
    expected = original[0] * torch.tensor(1 - 5 * (144 / 64) * 0.01)
    assert torch.equal(weights[("hires", 0)][0], expected)


def test_hr_stop_zero_keeps_the_base_weights_in_hires_like_the_source():
    c = config(d1=4, d2=3, st1=-1, st2=0)
    trace_nui, trace_src = run_both(c, [("base", 8, 4), ("hires", 16, 3)], hires=True)
    assert_same(trace_nui, trace_src)
    base = [w for n, _, w, _, _ in trace_nui if n == "base"][0]
    assert all(torch.equal(w[0], base[0]) for n, _, w, _, _ in trace_nui if n == "hires")


def test_contrast2_without_hires_and_with_dis_offset():
    for c in (config(con2=5, col3=-4), config(bri=6, dis=1), config(con2=1, dis=0.5)):
        trace_nui, trace_src = run_both(c, [("base", 8, 6)], hires=False)
        assert_same(trace_nui, trace_src)


@pytest.mark.parametrize("prediction", ["epsilon", "v_prediction"])
def test_contrast2_shifts_the_denoised_prediction(prediction):
    c = config(con2=5, bri=2, col1=-3)
    state = cdt.TunerState(c, sdxl=False, hr=False, batch=1)
    unet = TinyUnet()
    sched = SimpleNamespace(alphas_cumprod=ALPHAS, config={"prediction_type": prediction})
    hook = cdt.PassHook(cdt.Job(), state, "base", unet, None, sched, SimpleNamespace(_num_timesteps=4), prediction, 4)
    hook.install()
    try:
        hook.step = 2  # total - 2
        sample = torch.randn(2, 4, 8, 8, generator=torch.Generator().manual_seed(2))
        t = 400
        out = unet(sample, torch.tensor(t)).sample
        plain = unet.eps(sample)
    finally:
        hook.remove()
    sa, s1a = scales(t)
    x0 = (lambda o: (sample - s1a * o) / sa) if prediction == "epsilon" else (lambda o: sa * sample - s1a * o)
    shift = [5 * 0.02] + src_colorcalc([2, -3, 0, 0], False)
    expected = x0(plain) - torch.tensor(shift).view(1, 4, 1, 1) * 20 / 3
    assert torch.allclose(x0(out), expected, atol=1e-5)


@pytest.mark.parametrize("mode", ["Horizontal", "Vertical"])
@pytest.mark.parametrize("sdxl", [False, True])
def test_colour_map_matches_source_including_hires_restart(mode, sdxl):
    c = config(colors="5 0 0;0 0 5;-5 5 0;0 -5 5", split_ratio="2,1,3;3,4,6", split_by=mode, map_stop_step=3,
               map_strength=1.4, d1=1)
    trace_nui, trace_src = run_both(c, [("base", 8, 5), ("hires", 16, 4)], hires=True, batch=2, sdxl=sdxl)
    assert_same(trace_nui, trace_src)
    split = {(n, s): not torch.allclose(cnd, unc) for n, s, _, cnd, unc in trace_nui}  # cond -shift, uncond +shift
    assert [split[("base", s)] for s in range(5)] == [True, True, True, False, False]
    assert [split[("hires", s)] for s in range(4)] == [True, True, True, False]


def test_colour_map_alone_colours_only_the_first_hires_step_like_the_source():
    c = config(colors="5 0 0;-5 0 0", map_stop_step=3)
    trace_nui, trace_src = run_both(c, [("base", 8, 5), ("hires", 16, 4)], hires=True)
    assert_same(trace_nui, trace_src)
    split = [not torch.allclose(cnd, unc) for n, _, _, cnd, unc in trace_nui if n == "hires"]
    assert split == [True, False, False, False]


@pytest.mark.parametrize("prediction", ["epsilon", "v_prediction"])
def test_colour_map_without_cfg_and_with_pag_rows(prediction):
    c = config(colors="5 0 0;0 0 5", map_stop_step=2)
    for groups, guided in ((1, False), (3, True), (2, False)):
        state = cdt.TunerState(c, sdxl=False, hr=False, batch=1)
        unet = TinyUnet()
        sched = SimpleNamespace(alphas_cumprod=ALPHAS, config={"prediction_type": prediction})
        hook = cdt.PassHook(cdt.Job(), state, "base", unet, None, sched, SimpleNamespace(_num_timesteps=4),
                            prediction, 4, guided=guided).install()
        lat = torch.randn(1, 4, 8, 8, generator=torch.Generator().manual_seed(3))
        try:
            out = unet(lat.repeat(groups, 1, 1, 1), torch.tensor(700)).sample
        finally:
            hook.remove()
        seen = unet.seen[-1][0]
        x = torch.cat([lat, lat])
        src = SrcScript(*allsets_of(c), hr=False, batch=1, isxl=False, model=TinyUnet())
        src.denoiser_callback(x, 0)
        cond, uncond = x[:1], x[1:]
        rows = ([uncond] if guided else [cond]) + [cond] * (groups - 1)
        assert torch.allclose(seen, torch.cat(rows), atol=1e-6)
        sa, s1a = scales(700)
        x0 = (lat.repeat(groups, 1, 1, 1) - s1a * out) / sa if prediction == "epsilon" else \
            sa * lat.repeat(groups, 1, 1, 1) - s1a * out
        edited = torch.cat(rows)
        eps = unet.eps(edited)
        want = (edited - s1a * eps) / sa if prediction == "epsilon" else sa * edited - s1a * eps
        assert torch.allclose(x0, want, atol=1e-5)


def test_apply_once_stops_follow_up_passes():
    c = config(d1=3, con2=2, apply_once=True)
    trace_nui, trace_src = run_both(c, [("base", 8, 4), ("adetailer", 8, 4)], hires=False, opts=["Apply once"])
    assert_same(trace_nui, trace_src)
    original = TinyUnet().weights()
    assert all(torch.equal(w[0], original[0]) for n, _, w, _, _ in trace_nui if n == "adetailer")
    trace_nui, trace_src = run_both(config(d1=3, con2=2), [("base", 8, 4), ("adetailer", 8, 4)], hires=False)
    assert_same(trace_nui, trace_src)
    assert not torch.equal([w for n, _, w, _, _ in trace_nui if n == "adetailer"][0][0], original[0])


def test_each_hires_run_starts_from_the_base_state():
    c = config(d1=2, hd1=4, st2=2)
    job = cdt.Job(True)
    make = lambda: cdt.TunerState(c, sdxl=False, hr=True, batch=1)  # noqa: E731
    base = job.state_for("base", make)
    base.denoiser(None, (2, 4, 8, 8), 0, SimpleNamespace(apply=lambda f: None, restore=lambda: None))
    job.finish("base")
    first = job.state_for("hires", make)
    first.denoiser(None, (2, 4, 16, 16), 0, SimpleNamespace(apply=lambda f: None, restore=lambda: None))
    second = job.state_for("hires", make)
    assert second.pas == 0 and second.done == [True, False] and first.pas == 1
    follow = job.state_for("adetailer", make)
    assert follow is second and follow.ended


class _Meta(torch.nn.Module):
    """A sequential-offload style module: meta parameters, real ones in the accelerate hook's weights map."""

    def __init__(self, conv):
        super().__init__()
        self.weight = torch.nn.Parameter(torch.empty(conv.weight.shape, device="meta"))
        self.bias = torch.nn.Parameter(torch.empty(conv.bias.shape, device="meta"))
        table = {"weight": conv.weight.detach().clone(), "bias": conv.bias.detach().clone()}
        self._hf_hook = SimpleNamespace(weights_map=table)


def test_weights_write_into_offload_maps_and_peft_base_layers():
    unet = TinyUnet()
    offloaded = _Meta(unet.conv_in)
    unet.conv_in = offloaded
    unet.conv_out = torch.nn.Module()  # a PEFT LoraLayer keeps the real layer as base_layer
    unet.conv_out.base_layer = torch.nn.Conv2d(8, 4, 3, padding=1)
    before = offloaded._hf_hook.weights_map["weight"].clone()
    weights = cdt.Weights(unet)
    weights.apply(cdt.fine_ratios([5, 0, 3]))
    assert torch.equal(offloaded._hf_hook.weights_map["weight"], before * torch.tensor(0.95))
    bias = unet.conv_out.base_layer.bias.detach().clone()
    weights.restore_all()
    assert torch.equal(offloaded._hf_hook.weights_map["weight"], before)
    assert not torch.equal(bias, unet.conv_out.base_layer.bias)
    with pytest.raises(EngineError, match="does not have"):
        cdt.Slot(unet, "conv_missing.weight")


def _pipeline():
    diffusers = pytest.importorskip("diffusers")
    vae = diffusers.AutoencoderKL(in_channels=3, out_channels=3, latent_channels=4,
                                  down_block_types=("DownEncoderBlock2D",) * 4, up_block_types=("UpDecoderBlock2D",) * 4,
                                  block_out_channels=(4, 8, 16, 16), layers_per_block=1, norm_num_groups=4)
    unet = diffusers.UNet2DConditionModel(sample_size=8, in_channels=4, out_channels=4,
                                          down_block_types=("CrossAttnDownBlock2D",), up_block_types=("CrossAttnUpBlock2D",),
                                          block_out_channels=(8,), layers_per_block=1, norm_num_groups=4,
                                          cross_attention_dim=8, attention_head_dim=2)
    pipe = diffusers.StableDiffusionPipeline(vae=vae, text_encoder=None, tokenizer=None, unet=unet,
                                             scheduler=diffusers.EulerDiscreteScheduler(steps_offset=1),
                                             safety_checker=None, feature_extractor=None, requires_safety_checker=False)
    pipe.set_progress_bar_config(disable=True)
    return pipe


def _snapshot(pipe):
    names = list(cdt.UNET_KEYS)
    out = {n: cdt.Slot(pipe.unet, n).read().detach().clone() for n in names}
    out.update({n: cdt.Slot(pipe.vae, n).read().detach().clone() for n in cdt.VAE_KEYS + cdt.VAE_KEYS2})
    return out


def _generate(pipe, c, *, fail_at=None, seen=None):
    from sdfx.extensions import _chain_callback
    engine = SimpleNamespace(kind="sd15", sdxl=False, _cd_tuner_job=cdt.Job(False))
    embeds = torch.randn(1, 77, 8, generator=torch.Generator().manual_seed(1))
    kw = dict(prompt_embeds=embeds, negative_prompt_embeds=torch.zeros_like(embeds), num_inference_steps=4,
              guidance_scale=5.0, height=64, width=64, output_type="pil",
              generator=[torch.Generator().manual_seed(9)])

    def watch(p, i, t, d):
        if seen is not None:
            seen.append(_snapshot(p))
        if fail_at is not None and i == fail_at:
            raise RuntimeError("interrupted")
        return d
    kw["callback_on_step_end"] = watch
    cleanups = []
    try:
        cdt.install(engine, pipe, SimpleNamespace(batch_size=1), kw, None, c, pass_="base", sched=pipe.scheduler,
                    chain=_chain_callback, cleanups=cleanups, cfg_scale=5.0)
        with torch.inference_mode():
            return pipe(**kw).images[0]
    finally:
        for fn in reversed(cleanups):
            fn()


def test_pipeline_run_edits_while_sampling_and_restores_after_success_and_error():
    pipe = _pipeline()
    original = _snapshot(pipe)
    c = dict(cdt.DEFAULTS, d1=4, d2=-3, con1=2, sat=5, sat2=-4, st1=3, colors="5 0 0;0 0 5", con2=2)
    seen = []
    edited = _generate(pipe, c, seen=seen)
    after = _snapshot(pipe)
    assert all(torch.equal(original[k], after[k]) for k in original)
    w = original["conv_in.weight"]
    assert torch.equal(seen[0]["conv_in.weight"], w * torch.tensor(1 - 4 * 0.01))
    assert torch.equal(seen[2]["conv_in.weight"], seen[0]["conv_in.weight"])
    assert torch.equal(seen[3]["conv_in.weight"], w)  # Stop Step 3 restores before the fourth evaluation
    vae = cdt.VAE_KEYS[0]
    assert torch.equal(seen[0][vae], original[vae] * (1 + 5 * 0.075))
    assert torch.equal(seen[0][cdt.VAE_KEYS2[0]], original[cdt.VAE_KEYS2[0]] * (1 + -4 * 0.02))
    plain = _generate(pipe, dict(cdt.DEFAULTS))
    assert list(edited.getdata()) != list(plain.getdata())
    with pytest.raises(RuntimeError, match="interrupted"):
        _generate(pipe, c, fail_at=1)
    after = _snapshot(pipe)
    assert all(torch.equal(original[k], after[k]) for k in original)


def test_install_rejects_unsupported_models_and_combinations():
    pipe = _pipeline()
    from sdfx.extensions import _chain_callback
    common = dict(pass_="base", sched=pipe.scheduler, chain=_chain_callback, cfg_scale=5.0)
    kw = {"prompt_embeds": torch.zeros(1, 77, 8)}
    with pytest.raises(EngineError, match="not 'unsupported'"):
        cdt.install(SimpleNamespace(kind="unsupported", sdxl=False), pipe, None, kw, None, {"d1": 1}, cleanups=[], **common)
    cleanups = []
    with pytest.raises(EngineError, match="Color Map cannot combine with APG"):
        cdt.install(SimpleNamespace(kind="sd15", sdxl=False), pipe, None, kw, None, {"colors": "5 0 0;0 0 5"},
                    cleanups=cleanups, conflicts=["APG"], **common)
    with pytest.raises(EngineError, match="Neutral Prompt"):
        cdt.install(SimpleNamespace(kind="sd15", sdxl=False), pipe, None, kw, None, {"con2": 1},
                    cleanups=cleanups, composed=True, **common)
    # detail alone composes with branch generation
    cdt.install(SimpleNamespace(kind="sd15", sdxl=False), pipe, None, kw, None, {"d1": 1}, cleanups=cleanups,
                composed=True, **common)
    for fn in reversed(cleanups):
        fn()


def test_nothing_set_installs_nothing_and_tags_are_stripped_from_prompts():
    from sdfx.prompt import strip_extra_networks
    state = cdt.TunerState(config(st1=3), sdxl=False, hr=False, batch=1)
    assert not state.active and not state.activec
    state = cdt.TunerState(config(colors="5 0 0", map_strength=0), sdxl=False, hr=False, batch=1)
    assert not state.activec
    assert strip_extra_networks("a cat <cdt:d1=2;col1=-3> <cdtc:1,1;H;5 0 0|-5 0 0;2;1><lora:x:1>") == "a cat  "
    from sdfx import dryrun, hooks
    assert "cd_tuner" in hooks.KNOWN_HOOKS
    assert "cd_tuner" in dryrun.FakeEngine().features()
