ALTER TABLE public.baselines
  ADD COLUMN IF NOT EXISTS mad          double precision,
  ADD COLUMN IF NOT EXISTS window_start timestamptz,
  ADD COLUMN IF NOT EXISTS window_end   timestamptz;
  
CREATE UNIQUE INDEX IF NOT EXISTS baselines_source_region_window_uq
  ON public.baselines (source_id, region, window_start, window_end);