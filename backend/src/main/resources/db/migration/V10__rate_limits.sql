-- Token buckets for rate limiting, shared by every app instance.
--
-- A bucket holds up to `capacity` tokens and refills continuously. A request takes
-- one token; with none left it is refused. Refill is computed from updated_at at
-- the moment of each request, inside a single UPDATE, so concurrent requests on one
-- bucket are serialized by its row lock and can never take more tokens than exist.
CREATE TABLE rate_limit_buckets (
    bucket_key VARCHAR(300) PRIMARY KEY,
    tokens DOUBLE PRECISION NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_rate_limit_updated ON rate_limit_buckets(updated_at);
