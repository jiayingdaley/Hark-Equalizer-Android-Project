on run argv
	if (count of argv) is not 1 then error "Expected DOCX path"
	set docAlias to POSIX file (item 1 of argv)

	tell application "Microsoft Word"
		activate
		open docAlias
		set documentReady to false
		repeat 60 times
			if (count of documents) is greater than 0 then
				try
					set docRef to document 1
					set docName to name of docRef
					if docName is not missing value then
						set documentReady to true
						exit repeat
					end if
				end try
			end if
			delay 1
		end repeat
		if documentReady is false then error "Word did not finish opening the document"

		set fieldIndex to 1
		repeat
			try
				set currentField to field fieldIndex of text object of docRef
				update field currentField
				set fieldIndex to fieldIndex + 1
			on error
				exit repeat
			end try
		end repeat

		set tocIndex to 1
		repeat
			try
				set currentTOC to table of contents tocIndex of docRef
				update currentTOC
				set tocIndex to tocIndex + 1
			on error
				exit repeat
			end try
		end repeat

		set tofIndex to 1
		repeat
			try
				set currentTOF to table of figures tofIndex of docRef
				update currentTOF
				set tofIndex to tofIndex + 1
			on error
				exit repeat
			end try
		end repeat

		close docRef saving yes
	end tell
end run
