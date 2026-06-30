import { createClient } from '@supabase/supabase-js';

export const supabase = createClient(
  'https://klfqsadfbhjpiwozuezk.supabase.co',
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtsZnFzYWRmYmhqcGl3b3p1ZXprIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODI2NTkzMzcsImV4cCI6MjA5ODIzNTMzN30.ubKkKjBYwmRNbdgvkLDhvHgMF1f4B-Z8VTo6CsOdSJs'
);
