#!/bin/bash

# Prevent script from continuing if a critical command fails
set -e

# Default values
THREADS=16
OUTPUT_DIR="$HOME/Downloads"
URL=""

# Help documentation
show_help() {
    echo "Usage: $0 [OPTIONS] <source_url>"
    echo ""
    echo "Required Parameters:"
    echo "  <source_url>          The source site / URL of the file to download."
    echo ""
    echo "Optional Parameters:"
    echo "  -t, --threads NUM     Number of threads/connections per server (Default: 16)"
    echo "  -o, --output DIR      Output location directory (Default: ~/Downloads)"
    echo "  -h, --help            Show this help menu"
}

# Parse named arguments manually to seamlessly handle long options (--threads, --output)
while [[ $# -gt 0 ]]; do
    case "$1" in
        -t|--threads)
            THREADS="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        -*)
            echo "Error: Unknown option $1" >&2
            show_help
            exit 1
            ;;
        *)
            if [ -z "$URL" ]; then
                URL="$1"
                shift
            else
                echo "Error: Only one source URL can be processed at a time." >&2
                show_help
                exit 1
            fi
            ;;
    esac
done

# Validate required parameter
if [ -z "$URL" ]; then
    echo "Error: Missing required parameter 'source site' (URL)." >&2
    show_help
    exit 1
fi

# Ensure aria2c is installed on the system
if ! command -v aria2c &> /dev/null; then
    echo "Error: aria2c is not installed. Please install it via your package manager." >&2
    exit 1
fi

# Safely handle string literal tilde expansion if passed as '-o ~/Folder'
if [[ "$OUTPUT_DIR" == "~"* ]]; then
    OUTPUT_DIR="${OUTPUT_DIR/#\~/$HOME}"
fi

# Create target directory structure if it doesn't already exist
mkdir -p "$OUTPUT_DIR"

# Print operational parameters
echo "========================================"
echo " Starting Improved aria2c Download"
echo "========================================"
echo "Source:      $URL"
echo "Destination: $OUTPUT_DIR"
echo "Threads:     $THREADS"
echo "========================================"

# Run aria2c with performance-optimized flag arguments
aria2c \
    --max-connection-per-server="$THREADS" \
    --split="$THREADS" \
    --min-split-size=1M \
    --file-allocation=falloc \
    --continue=true \
    --max-concurrent-downloads=5 \
    --stream-piece-selector=geom \
    --summary-interval=5 \
    --dir="$OUTPUT_DIR" \
    "$URL"
