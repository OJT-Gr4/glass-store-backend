-- Preserve uploaded/custom photos; only replace absent or the previous default avatar.
UPDATE db_identity.users
SET avatar_url = 'https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQJAy3MrvYrmubxOdwfv33LI41s2yC06qjU4-qkqMyF4w&s'
WHERE avatar_url IS NULL OR TRIM(avatar_url) = ''
   OR avatar_url = 'https://i.pinimg.com/236x/3e/f1/fc/3ef1fcc3b6cbb97199f961bb0b3ead3f.jpg';
