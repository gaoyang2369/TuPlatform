ALTER TABLE profiles ADD COLUMN avatar_url text;
CREATE UNIQUE INDEX profiles_avatar_url ON profiles (avatar_url) WHERE avatar_url IS NOT NULL;
