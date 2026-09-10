# Security Configuration Guide

## API Credentials Setup

This app requires API credentials for Microsoft Translator (via RapidAPI) for online translation.

### ⚠️ CRITICAL: Never commit API keys to Git!

API keys should NEVER be hardcoded in source files. This app uses the following secure approach:

### Development Setup

1. **Copy the template file:**
   ```bash
   cp local.properties.example local.properties
   ```

2. **Get your API key from RapidAPI:**
   - Visit: https://rapidapi.com/microsoft-azure-org-microsoft-cognitive-services/api/microsoft-translator-text
   - Sign up (free tier available)
   - Subscribe to the API
   - Copy your API key

3. **Add credentials to local.properties:**
   ```properties
   RAPIDAPI_KEY=your_actual_api_key_here
   RAPIDAPI_HOST=microsoft-translator-text.p.rapidapi.com
   ```

4. **Build the app:**
   ```bash
   ./gradlew assembleDebug
   ```

### Security Features

✅ **local.properties** - Not committed to Git (in .gitignore)
✅ **BuildConfig injection** - Keys loaded at compile time, not in source code
✅ **No hardcoded secrets** - All sensitive data in local.properties
✅ **Safe for CI/CD** - Can use environment variables for automated builds

### Production Deployment

For production builds, use one of these approaches:

#### Option 1: Environment Variables (Recommended for CI/CD)
```bash
# In CI/CD environment
export RAPIDAPI_KEY="your-production-key"
export RAPIDAPI_HOST="microsoft-translator-text.p.rapidapi.com"

# Then build
./gradlew assembleRelease
```

#### Option 2: Signed APK with Secrets Management
```bash
# Use a secure secrets management system:
# - AWS Secrets Manager
# - Google Cloud Secret Manager
# - GitHub Secrets
# - Azure Key Vault
```

#### Option 3: Secure Backend (Most Secure)
Deploy your own backend server that:
- Stores the API key securely
- Validates requests from your app
- Returns translations to the app
- Prevents abuse through rate limiting

### If Your API Key Is Exposed

⚠️ **IMMEDIATE ACTIONS:**

1. **Regenerate the key immediately:**
   - Log into RapidAPI
   - Go to API Credentials
   - Regenerate/delete the compromised key

2. **Clean Git history:**
   ```bash
   # Option 1: Using BFG Repo-Cleaner (recommended)
   bfg --delete-files local.properties
   bfg --delete-files "*.properties"
   git reflog expire --expire=now --all
   git gc --prune=now --aggressive

   # Option 2: Using git filter-branch
   git filter-branch --tree-filter 'rm -f local.properties' -- --all
   ```

3. **Force push to remote** (only if you control the repository):
   ```bash
   git push --force --all
   git push --force --tags
   ```

4. **Monitor API usage** on RapidAPI dashboard for suspicious activity

### Checking for Exposed Keys

Search your repository for accidental key commits:
```bash
# Check git history
git log --all -p | grep -i "RAPIDAPI_KEY"

# Check all branches
git grep -i "RAPIDAPI_KEY"
```

### Best Practices

✅ DO:
- Keep API keys in local.properties (development) or environment variables (production)
- Rotate API keys regularly
- Use minimal permissions for API keys
- Monitor API usage for suspicious activity
- Use different keys for development and production

❌ DON'T:
- Hardcode API keys in source files
- Commit API keys to Git
- Share API keys via email or chat
- Use the same key across multiple environments
- Log or print API keys anywhere

### Support

For security issues, do not open public issues. Instead:
1. Document the issue privately
2. Contact the app maintainers securely
3. Provide details on how to reproduce
4. Allow time for a fix before public disclosure
