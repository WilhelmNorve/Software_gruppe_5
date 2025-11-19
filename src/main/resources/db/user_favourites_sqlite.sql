PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS user_favorites (
                                              id         INTEGER PRIMARY KEY,
                                              user_id    INTEGER NOT NULL,
                                              quay_id    TEXT    NOT NULL,           -- f.eks. "NSR:Quay:101991"
                                              note       TEXT,
                                              position   INTEGER,
                                              created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                              CHECK (quay_id GLOB 'NSR:Quay:[0-9]*'),
                                              UNIQUE (user_id, quay_id),
                                              FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_favorites_user
    ON user_favorites(user_id);
