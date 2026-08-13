package scheduler

import (
	"context"
	"fmt"

	"github.com/veritasvpn/lib/logging"
	"github.com/veritasvpn/services/wg-manager/internal/model"
	"github.com/veritasvpn/services/wg-manager/internal/repository"
)

type Scheduler struct {
	postgres *repository.Postgres
	log      *logging.Logger
}

func New(postgres *repository.Postgres, log *logging.Logger) *Scheduler {
	return &Scheduler{
		postgres: postgres,
		log:      log,
	}
}

func (s *Scheduler) SelectServer(ctx context.Context, preferredRegion string) (*model.Server, error) {
	servers, err := s.postgres.ListOnlineServers(ctx)
	if err != nil {
		return nil, fmt.Errorf("list online servers: %w", err)
	}

	if len(servers) == 0 {
		return nil, fmt.Errorf("no online servers available")
	}

	if preferredRegion != "" {
		var filtered []model.Server
		for _, srv := range servers {
			if srv.Region == preferredRegion {
				filtered = append(filtered, srv)
			}
		}
		if len(filtered) == 0 {
			return nil, fmt.Errorf("no online servers in region %s", preferredRegion)
		}
		servers = filtered
	}

	selected := &servers[0]
	s.log.Info("server selected",
		"server_id", selected.ID,
		"hostname", selected.Hostname,
		"load_factor", selected.LoadFactor,
		"region", selected.Region,
	)
	return selected, nil
}
