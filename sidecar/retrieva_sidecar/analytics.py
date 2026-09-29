"""Analytics over a loaded memory. Pure functions of DataFrames; nothing here touches the request path."""
from __future__ import annotations

import pandas as pd

# Predicate polarity. MUST match retrieva-core's Lexicon (a test compares against the reference implementation).
POS = "improve increase raise boost enhance promote support strengthen help protect benefit aid enable accelerate stimulate reinforce confirm".split()
NEG = "reduce decrease lower harm worsen impair weaken inhibit prevent damage undermine hinder diminish suppress disrupt contradict refute delay".split()
LEMMA_POLARITY = {**{w: 1 for w in POS}, **{w: -1 for w in NEG}}
ADJ_POLARITY = {**{w: 1 for w in "safe effective beneficial healthy true accurate reliable helpful useful good positive secure legitimate valid proven".split()},
                **{w: -1 for w in ("unsafe dangerous ineffective harmful unhealthy false inaccurate unreliable useless bad negative insecure "
                                  "illegitimate invalid harm damage toxic risky").split()}}


def polarity(predicate: str, obj: str) -> int:
    neg = predicate.startswith("not ")
    base = LEMMA_POLARITY.get(predicate[4:] if neg else predicate, 0)
    adj = next((ADJ_POLARITY[w] for w in obj.split() if w in ADJ_POLARITY), 0)
    pol = base * adj if base and adj else (base or adj)
    return -pol if neg else pol


def with_polarity(triples: pd.DataFrame) -> pd.DataFrame:
    out = triples.copy()
    out["polarity"] = [polarity(p, o) for p, o in zip(out["predicate"], out["object"])]
    return out


def host_stats(triples: pd.DataFrame) -> pd.DataFrame:
    """Per-host volume, mean trust and freshness."""
    g = triples.groupby("host")
    return (pd.DataFrame({"triples": g.size(), "mean_trust": g["trust"].mean(), "newest": g["added"].max(), "oldest": g["added"].min()})
            .sort_values("triples", ascending=False))


def contested(triples: pd.DataFrame, min_hosts: int = 2) -> pd.DataFrame:
    """Subject/object pairs that credible hosts assert with opposite polarity: where the evidence is genuinely split."""
    t = with_polarity(triples)
    t = t[t["polarity"] != 0]
    g = t.groupby(["subject", "object"])
    out = pd.DataFrame({
        "hosts": g["host"].nunique(),
        "for_hosts": t[t["polarity"] > 0].groupby(["subject", "object"])["host"].nunique(),
        "against_hosts": t[t["polarity"] < 0].groupby(["subject", "object"])["host"].nunique(),
    }).fillna(0).astype(int)
    out = out[(out["for_hosts"] > 0) & (out["against_hosts"] > 0) & (out["hosts"] >= min_hosts)]
    return out.sort_values("hosts", ascending=False)


def trust_weighted_balance(triples: pd.DataFrame) -> pd.DataFrame:
    """Per subject/object: trust-weighted support minus opposition, one vote per host (mirrors the engine's one-voice-per-host rule)."""
    t = with_polarity(triples)
    t = t[t["polarity"] != 0]
    per_host = t.groupby(["subject", "object", "host"]).agg(polarity=("polarity", "mean"), trust=("trust", "max")).reset_index()
    per_host["signed"] = per_host["polarity"].apply(lambda x: 1 if x > 0 else -1) * per_host["trust"]
    return (per_host.groupby(["subject", "object"]).agg(balance=("signed", "sum"), hosts=("host", "nunique"))
            .sort_values("balance"))


def age_histogram(triples: pd.DataFrame, now: pd.Timestamp | None = None) -> pd.Series:
    """How old the evidence is (source-added time), in year buckets."""
    now = now or pd.Timestamp.now(tz="UTC")
    years = ((now - triples["added"]).dt.days // 365).clip(lower=0)
    return years.value_counts().sort_index().rename("triples")


def route_summary(routes: pd.DataFrame) -> dict:
    if routes.empty:
        return {"routes": 0}
    return {"routes": int(len(routes)), "verdicts": routes["verdict"].value_counts().to_dict(),
            "mean_steps": float(routes["n_steps"].mean()), "most_replayed": routes.sort_values("hits", ascending=False).head(5)["key"].tolist()}
