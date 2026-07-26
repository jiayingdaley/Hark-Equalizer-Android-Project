on run argv
	if (count of argv) is not 2 then error "Expected DOCX and PDF paths"
	set docAlias to POSIX file (item 1 of argv)
	set docPath to item 1 of argv
	set pdfPath to item 2 of argv

	tell application "Microsoft Word"
		activate
		open docAlias
		delay 3
		set docRef to active document
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
		save as docRef file name docPath file format format document
		delay 2
		save as docRef file name pdfPath file format format PDF
		close docRef saving yes
	end tell
end run
