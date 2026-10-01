import time

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest
from pydantic import BaseModel
from sklearn.feature_extraction.text import HashingVectorizer, TfidfVectorizer
from sklearn.linear_model import LogisticRegression

app=FastAPI(title="ServiceOps Incident Classifier",version="0.2.0")
examples=["payment timeout latency database connection pool","checkout p95 latency after deployment","user cannot login oauth authentication failure","permission denied identity token expired","service unavailable outage pods crashloop","api health check failing unavailable"]
labels=["PERFORMANCE","PERFORMANCE","SECURITY","SECURITY","AVAILABILITY","AVAILABILITY"]
vectorizer=TfidfVectorizer(ngram_range=(1,2),lowercase=True); model=LogisticRegression(max_iter=1000).fit(vectorizer.fit_transform(examples),labels)

# Retrieval embeddings are stateless: a hashing vectorizer needs no fitted vocabulary,
# so every replica and every backfill produces identical vectors for the same text.
# EMBEDDING_DIM must match the VECTOR(n) column width in V1__init.sql.
# Scraped by Prometheus so classifier liveness is observable directly. Inferring it from the
# API's fallback counters only works while the API is serving traffic: a classifier that dies
# during a quiet period would otherwise go unnoticed until the next incident arrives.
REQUESTS=Counter("classifier_requests_total","Classifier requests",["endpoint","outcome"])
LATENCY=Histogram("classifier_request_duration_seconds","Classifier request duration",["endpoint"])

EMBEDDING_DIM=256
embedder=HashingVectorizer(n_features=EMBEDDING_DIM,ngram_range=(1,2),lowercase=True,alternate_sign=False,norm="l2")

class Incident(BaseModel):
    title:str
    description:str
    service:str|None=None

class EmbedRequest(BaseModel):
    texts:list[str]

@app.middleware("http")
async def _record_metrics(request:Request,call_next):
    endpoint=request.url.path
    started=time.perf_counter()
    try:
        response=await call_next(request)
    except Exception:
        REQUESTS.labels(endpoint=endpoint,outcome="error").inc()
        raise
    if endpoint not in ("/metrics",):
        LATENCY.labels(endpoint=endpoint).observe(time.perf_counter()-started)
        REQUESTS.labels(endpoint=endpoint,outcome="ok" if response.status_code<400 else "error").inc()
    return response

@app.get("/metrics")
def metrics(): return Response(generate_latest(),media_type=CONTENT_TYPE_LATEST)

@app.get("/health")
def health(): return {"status":"ok","model":"tfidf-logistic-v1","embeddingModel":"hashing-l2-v1","embeddingDimension":EMBEDDING_DIM}

@app.post("/predict")
def predict(incident:Incident):
    text=incident.title+" "+incident.description; probabilities=model.predict_proba(vectorizer.transform([text]))[0]; idx=probabilities.argmax(); category=model.classes_[idx]; priority="P1" if any(word in text.lower() for word in ("outage","payment","data loss")) else "P2"; group={"SECURITY":"identity-platform","PERFORMANCE":"platform-reliability","AVAILABILITY":"platform-reliability"}[category]; return {"category":category,"priority":priority,"assignmentGroup":group,"confidence":round(float(probabilities[idx]),3),"modelVersion":"tfidf-logistic-v1"}

@app.post("/embed")
def embed(request:EmbedRequest):
    if not request.texts: return {"model":"hashing-l2-v1","dimension":EMBEDDING_DIM,"vectors":[]}
    matrix=embedder.transform(request.texts).toarray()
    return {"model":"hashing-l2-v1","dimension":EMBEDDING_DIM,"vectors":[[round(float(value),6) for value in row] for row in matrix]}
