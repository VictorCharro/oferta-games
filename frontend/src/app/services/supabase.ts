import { createClient } from '@supabase/supabase-js';

// storage/persistSession so o browser tem: sem essa guarda, o supabase-js referencia
// `localStorage` direto na construcao do client, que nao existe em Node (SSR), quebrando toda
// renderizacao server-side com "ReferenceError: localStorage is not defined".
const noBrowser = typeof window === 'undefined';

export const supabase = createClient(
  'https://gxukrzmiloqdmtcgigqm.supabase.co',
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imd4dWtyem1pbG9xZG10Y2dpZ3FtIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQwNjUyOTIsImV4cCI6MjA5OTY0MTI5Mn0.0UZjhMClTtSQb5_XjC5dRwauqGtXrE48HMMSKbDnA30',
  noBrowser
    ? { auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false } }
    : undefined
);
