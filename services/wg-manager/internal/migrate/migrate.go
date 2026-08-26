package migrate

import (
	"context"
	"embed"
	"fmt"
	"sort"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed *.sql
var files embed.FS

// Up applies embedded numbered SQL migrations in lexical order.
// Statements are written to be idempotent (IF NOT EXISTS).
func Up(ctx context.Context, pool *pgxpool.Pool) error {
	entries, err := files.ReadDir(".")
	if err != nil {
		return fmt.Errorf("wg-manager migrate: list: %w", err)
	}
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() || len(e.Name()) < 5 || e.Name()[len(e.Name())-4:] != ".sql" {
			continue
		}
		names = append(names, e.Name())
	}
	sort.Strings(names)
	for _, name := range names {
		body, err := files.ReadFile(name)
		if err != nil {
			return fmt.Errorf("wg-manager migrate: read %s: %w", name, err)
		}
		if _, err := pool.Exec(ctx, string(body)); err != nil {
			return fmt.Errorf("wg-manager migrate: apply %s: %w", name, err)
		}
	}
	return nil
}
